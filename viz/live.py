"""Eventos ao vivo para a página: segue os logs enquanto a rodada acontece.

Duas fontes, escolhidas por VIZ_SOURCE:

local  segue os *.jsonl da rodada mais recente em logs/ (o que o scripts/local-run.sh
       escreve). LIVE_DIR fixa um diretório. Quando aparece uma rodada mais nova, troca.
k8s    segue `kubectl logs -f` de cada pod do observador e dos sync-i no namespace
       K8S_NAMESPACE (padrão maekawa). Pod novo (mesmo nome, outro uid) é outra fonte.

O Hub guarda os eventos de cada fonte viva. Quando uma fonte some (pod apagado, rodada
trocada), os eventos dela saem e todos os navegadores recebem um snapshot novo. Assim
a tela sempre mostra só a rodada atual, desde o começo dela.
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable

POLL_S = 0.2
MAX_EVENTS = 200_000

_children: list[subprocess.Popen] = []
_children_lock = threading.Lock()


def _spawn(args: list[str], **kw) -> subprocess.Popen:
    proc = subprocess.Popen(args, **kw)
    with _children_lock:
        _children[:] = [p for p in _children if p.poll() is None]
        _children.append(proc)
    return proc


def shutdown() -> None:
    """Encerra os kubectl abertos (logs -f, port-forward) para não ficarem órfãos."""
    with _children_lock:
        for p in _children:
            if p.poll() is None:
                p.terminate()


class Hub:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._events: list[tuple[str, dict]] = []  # (fonte, evento) na ordem de chegada
        self._subs: list[queue.Queue] = []
        self.status = "iniciando"

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue()
        with self._lock:
            q.put(self._snapshot_locked())
            self._subs.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            if q in self._subs:
                self._subs.remove(q)

    def publish(self, source: str, event: dict) -> None:
        with self._lock:
            self._events.append((source, event))
            if len(self._events) > MAX_EVENTS:
                del self._events[: len(self._events) - MAX_EVENTS]
            msg = {"type": "event", "event": event}
            for q in self._subs:
                q.put(msg)

    def drop_sources(self, keep: Callable[[str], bool]) -> None:
        """Remove eventos de fontes que já não existem e manda snapshot novo."""
        with self._lock:
            before = len(self._events)
            self._events = [(s, e) for s, e in self._events if keep(s)]
            if len(self._events) == before:
                return
            snap = self._snapshot_locked()
            for q in self._subs:
                q.put(snap)

    def set_status(self, text: str) -> None:
        with self._lock:
            if text == self.status:
                return
            self.status = text
            for q in self._subs:
                q.put({"type": "status", "status": text})

    def _snapshot_locked(self) -> dict:
        return {"type": "snapshot", "status": self.status, "events": [e for _, e in self._events]}


def parse_line(line: str, keep: Callable[[dict], bool]) -> dict | None:
    line = line.strip()
    if not line.startswith("{"):
        return None
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        return None
    return event if keep(event) else None


# ------------------------------------------------------------------ fonte local

def _newest_run(logs: Path) -> Path | None:
    best, best_t = None, -1.0
    if not logs.is_dir():
        return None
    for overlay in logs.iterdir():
        if not overlay.is_dir():
            continue
        for rep in overlay.iterdir():
            if not rep.is_dir():
                continue
            files = list(rep.glob("*.jsonl"))
            if not files:
                continue
            t = max(f.stat().st_mtime for f in files)
            if t > best_t:
                best, best_t = rep, t
    return best


def follow_local(hub: Hub, logs: Path, keep: Callable[[dict], bool], fixed: Path | None = None) -> None:
    current: Path | None = None
    handles: dict[Path, tuple[object, str]] = {}  # arquivo -> (handle, resto de linha incompleta)
    while True:
        run = fixed or _newest_run(logs)
        if run != current:
            for h, _ in handles.values():
                h.close()
            handles.clear()
            old = current
            current = run
            if old is not None:
                prefix = str(old) + os.sep
                hub.drop_sources(lambda s: not s.startswith(prefix))
            hub.set_status(f"seguindo {current.relative_to(logs.parent)}" if current else "sem rodada em logs/")
        if current is not None:
            for f in sorted(current.glob("*.jsonl")):
                if f not in handles:
                    handles[f] = (f.open(encoding="utf-8"), "")
            for f, (h, rest) in list(handles.items()):
                chunk = h.read()
                if not chunk:
                    continue
                data = rest + chunk
                lines = data.split("\n")
                handles[f] = (h, lines[-1])  # última pode estar pela metade
                for line in lines[:-1]:
                    event = parse_line(line, keep)
                    if event is not None:
                        hub.publish(str(f), event)
        time.sleep(POLL_S)


# ------------------------------------------------------------------ fonte k8s

class PortForward:
    """`kubectl port-forward svc/observer` mantido vivo: recria quando sai ou quando o
    observador troca de pod (o run-experiment.sh apaga o pod a cada rodada)."""

    def __init__(self, namespace: str, local_port: int) -> None:
        self.namespace = namespace
        self.local_port = local_port
        self._proc: subprocess.Popen | None = None
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self) -> None:
        while True:
            proc = _spawn(
                ["kubectl", "-n", self.namespace, "port-forward", "svc/observer", f"{self.local_port}:8080"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            with self._lock:
                self._proc = proc
            proc.wait()
            time.sleep(1)

    def restart(self) -> None:
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                self._proc.terminate()

CONTINUOUS_ANNOTATION = "maekawa-continuous"
CLIENTS: dict = {}  # estado dos pods de clientes, atualizado a cada 2 s pelo follow_k8s
CLUSTER: dict = {}  # N_NODES do ConfigMap e quantos sync-i estão rodando
NODE_CHOICES = (4, 7, 13)  # N com conjuntos de votação (grade 2x2, Fano, plano de ordem 3)


def _pods(namespace: str) -> dict[str, str]:
    """uid -> nome dos pods Running do observador e dos sync-i. Atualiza CLIENTS de passagem."""
    out = subprocess.run(["kubectl", "-n", namespace, "get", "pods,configmaps", "-o", "json"],
                         capture_output=True, text=True, timeout=10, check=True).stdout
    items = json.loads(out).get("items", [])
    # sem anotação no pod, o modo vem do ConfigMap (CONTINUOUS), como no Job do kind-up.sh
    cfg = next((i.get("data") or {} for i in items
                if i.get("kind") == "ConfigMap" and i.get("metadata", {}).get("name") == "experiment"), {})
    cfg_continuous = cfg.get("CONTINUOUS") == "true"
    try:
        cfg_nodes = int(cfg.get("N_NODES") or 7)
    except ValueError:
        cfg_nodes = 7
    pods = {}
    clients = {"running": 0, "succeeded": 0, "failed": 0, "pending": 0, "continuous": False}
    for item in items:
        if item.get("kind") != "Pod":
            continue
        meta, phase = item.get("metadata", {}), item.get("status", {}).get("phase")
        name = meta.get("name", "")
        if meta.get("deletionTimestamp"):
            continue  # sendo apagado
        if (meta.get("labels") or {}).get("app") == "client":
            key = {"Running": "running", "Succeeded": "succeeded", "Failed": "failed"}.get(phase, "pending")
            clients[key] += 1
            ann = (meta.get("annotations") or {}).get(CONTINUOUS_ANNOTATION)
            if ann == "true" or (ann is None and cfg_continuous):
                clients["continuous"] = True
        elif phase == "Running" and (name.startswith("observer-") or name.startswith("sync-")):
            pods[meta["uid"]] = name
    CLIENTS.clear()
    CLIENTS.update(clients)
    CLUSTER.clear()
    try:
        think = [int(cfg.get("THINK_MIN_MS") or 0), int(cfg.get("THINK_MAX_MS") or 0)]
    except ValueError:
        think = None
    CLUSTER.update({"n_nodes": cfg_nodes, "sync_running": sum(1 for n in pods.values() if n.startswith("sync-")),
                    "think": think})
    return pods


def _follow_pod(hub: Hub, namespace: str, uid: str, name: str, keep: Callable[[dict], bool],
                alive: Callable[[str], bool]) -> None:
    proc = _spawn(["kubectl", "-n", namespace, "logs", "-f", "pod/" + name],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        for line in proc.stdout:  # type: ignore[union-attr]
            if not alive(uid):
                break
            event = parse_line(line, keep)
            if event is not None:
                hub.publish(uid, event)
    finally:
        proc.kill()


def follow_k8s(hub: Hub, namespace: str, keep: Callable[[dict], bool], pf: PortForward | None = None) -> None:
    live: dict[str, str] = {}
    lock = threading.Lock()

    def alive(uid: str) -> bool:
        with lock:
            return uid in live

    while True:
        try:
            pods = _pods(namespace)
        except (subprocess.SubprocessError, OSError) as err:
            hub.set_status(f"kubectl falhou: {err}")
            time.sleep(2)
            continue
        with lock:
            gone = [u for u in live if u not in pods]
            for u in gone:
                del live[u]
            new = {u: n for u, n in pods.items() if u not in live}
            live.update(new)
        if gone:
            hub.drop_sources(lambda s: alive(s))
        if pf is not None and any(n.startswith("observer-") for n in new.values()):
            # observador novo: o port-forward antigo aponta para um pod que sumiu
            time.sleep(1)
            pf.restart()
        for uid, name in new.items():
            threading.Thread(target=_follow_pod, args=(hub, namespace, uid, name, keep, alive), daemon=True).start()
        hub.set_status(f"k8s {namespace}: {len(pods)} pods" if pods else f"k8s {namespace}: nenhum pod rodando")
        time.sleep(2)


# ------------------------------------------------------------------ reiniciar a rodada (k8s)

_JOB_LABEL_KEYS = ("controller-uid", "job-name", "batch.kubernetes.io/controller-uid", "batch.kubernetes.io/job-name")


def _kubectl(namespace: str, *args: str, stdin: str | None = None, timeout: int = 240) -> str:
    res = subprocess.run(["kubectl", "-n", namespace, *args], input=stdin, capture_output=True, text=True,
                         timeout=timeout)
    if res.returncode != 0:
        raise RuntimeError((res.stderr or res.stdout).strip() or f"kubectl {' '.join(args)} falhou")
    return res.stdout


def _clean_job(job: dict) -> dict:
    """Job pronto para criar de novo: sem status, uid, seletor gerado e rótulos do controlador."""
    meta = job.get("metadata", {})
    spec = job.get("spec", {})
    tmeta = spec.get("template", {}).get("metadata", {})
    for labels in (meta.get("labels"), tmeta.get("labels")):
        if labels:
            for k in _JOB_LABEL_KEYS:
                labels.pop(k, None)
    for k in ("selector", "manualSelector"):
        spec.pop(k, None)
    return {
        "apiVersion": job.get("apiVersion", "batch/v1"),
        "kind": "Job",
        "metadata": {k: meta[k] for k in ("name", "namespace", "labels", "annotations") if k in meta},
        "spec": spec,
    }


class Restarter:
    """Reinicia a rodada no cluster: nós e observador com estado limpo e o Job de clientes de novo,
    com a mesma especificação e o ConfigMap que já está aplicado. Faz o mesmo que o miolo do
    run-experiment.sh, sem trocar overlay nem recolher logs."""

    def __init__(self, hub: Hub, namespace: str) -> None:
        self.hub = hub
        self.namespace = namespace
        self._lock = threading.Lock()
        self.state = {"running": False, "step": "", "error": None, "finished_at": None}

    def _set(self, **kw) -> None:
        with self._lock:
            self.state.update(kw)
        if kw.get("step"):
            self.hub.set_status(f"reiniciando: {kw['step']}")

    # o último Job visto, para conseguir disparar de novo depois de "Parar clientes"
    _template: dict | None = None

    def start(self, continuous: bool = False, nodes: int | None = None) -> bool:
        """nodes: troca o tamanho do cluster (N_NODES no ConfigMap, réplicas e Job) antes de subir."""
        if nodes is not None and nodes not in NODE_CHOICES:
            raise ValueError(f"nodes deve ser um de {NODE_CHOICES}")
        with self._lock:
            if self.state["running"]:
                return False
            self.state = {"running": True, "step": "começando", "error": None, "finished_at": None}
        threading.Thread(target=self._run, args=(continuous, nodes), daemon=True).start()
        return True

    def stop_clients(self) -> bool:
        """Apaga o Job: os clientes saem (o pedido em andamento fica sem COMMITTED). Nós seguem."""
        with self._lock:
            if self.state["running"]:
                return False
            self.state = {"running": True, "step": "parando os clientes", "error": None, "finished_at": None}
        threading.Thread(target=self._stop, daemon=True).start()
        return True

    def _stop(self) -> None:
        try:
            self._remember_job()
            _kubectl(self.namespace, "delete", "job", "clients", "--ignore-not-found", "--wait=true")
            self._set(running=False, step="clientes parados", finished_at=time.time())
        except Exception as err:  # noqa: BLE001
            self._set(running=False, step="falhou", error=str(err), finished_at=time.time())

    def _remember_job(self) -> None:
        try:
            self._template = _clean_job(json.loads(_kubectl(self.namespace, "get", "job", "clients", "-o", "json")))
        except RuntimeError:
            if self._template is None:
                self._template = self._base_job()  # sem Job no cluster nem lembrado: o de k8s/base

    def _base_job(self) -> dict | None:
        """Job de clientes de k8s/base, com a imagem que os nós estão usando."""
        base = Path(__file__).resolve().parent.parent / "k8s" / "base"
        try:
            docs = _kubectl(self.namespace, "kustomize", str(base)).split("\n---\n")
            job_yaml = next(d for d in docs if "\nkind: Job\n" in "\n" + d + "\n")
            job = json.loads(_kubectl(self.namespace, "create", "--dry-run=client", "-o", "json", "-f", "-",
                                      stdin=job_yaml))
            image = _kubectl(self.namespace, "get", "statefulset", "sync", "-o",
                             "jsonpath={.spec.template.spec.containers[0].image}").strip()
            if image:
                job["spec"]["template"]["spec"]["containers"][0]["image"] = image
            return _clean_job(job)
        except (RuntimeError, StopIteration, ValueError):
            return None

    @staticmethod
    def _as_continuous(job: dict, continuous: bool) -> dict:
        """CONTINUOUS no env do container (vence o ConfigMap) e a mesma informação na anotação dos pods."""
        job = json.loads(json.dumps(job))
        tmpl = job["spec"]["template"]
        container = tmpl["spec"]["containers"][0]
        env = [e for e in container.get("env", []) if e.get("name") != "CONTINUOUS"]
        env.append({"name": "CONTINUOUS", "value": "true" if continuous else "false"})
        container["env"] = env
        ann = tmpl.setdefault("metadata", {}).setdefault("annotations", {})
        ann[CONTINUOUS_ANNOTATION] = "true" if continuous else "false"
        return job

    def _run(self, continuous: bool, nodes: int | None) -> None:
        ns = self.namespace
        try:
            self._set(step="lendo o Job de clientes")
            self._remember_job()
            if self._template is None:
                raise RuntimeError("não achei o Job de clientes (nem no cluster nem em k8s/base)")
            job = self._as_continuous(self._template, continuous)
            target = nodes if nodes is not None else int(
                _kubectl(ns, "get", "statefulset", "sync", "-o", "jsonpath={.spec.replicas}").strip() or 7)
            job["spec"]["completions"] = target
            job["spec"]["parallelism"] = target
            cfg = json.loads(_kubectl(ns, "get", "configmap", "experiment", "-o", "json")).get("data", {})
            self._set(step="apagando o Job anterior")
            _kubectl(ns, "delete", "job", "clients", "--ignore-not-found", "--wait=true")
            # nós: escala para 0 e espera sumirem antes de subir de novo. Apagar os pods com o
            # StatefulSet ativo (e mudando de tamanho) deixa o "delete --wait" esperando pods que o
            # próprio StatefulSet recria com o mesmo nome.
            self._set(step="parando os nós")
            _kubectl(ns, "scale", "statefulset", "sync", "--replicas=0")
            try:
                _kubectl(ns, "wait", "--for=delete", "pod", "-l", "app=sync", "--timeout=120s")
            except RuntimeError as err:
                if "no matching resources" not in str(err):
                    raise
            if nodes is not None:
                # os grupos de votação dependem de N: nós, observador e clientes sobem com o N novo
                _kubectl(ns, "patch", "configmap", "experiment", "--type", "merge",
                         "-p", json.dumps({"data": {"N_NODES": str(nodes)}}))
            self._set(step=f"subindo {target} nós e o observador")
            _kubectl(ns, "scale", "statefulset", "sync", f"--replicas={target}")
            _kubectl(ns, "delete", "pod", "-l", "app=observer", "--wait=true")
            _kubectl(ns, "rollout", "status", "statefulset/sync", "--timeout=180s")
            _kubectl(ns, "rollout", "status", "deployment/observer", "--timeout=120s")
            time.sleep(2)  # como no run-experiment.sh: o observador termina de subir a escuta
            self._set(step="disparando os clientes")
            _kubectl(ns, "create", "-f", "-", stdin=json.dumps(job))
            cfg = json.loads(_kubectl(ns, "get", "configmap", "experiment", "-o", "json")).get("data", {})
            keys = ("N_NODES", "SYNC_ENABLED", "VARIANT") if continuous else ("N_NODES", "SYNC_ENABLED", "VARIANT", "ACCESSES")
            summary = ", ".join(f"{k}={cfg.get(k)}" for k in keys if k in cfg)
            what = "rodada contínua iniciada" if continuous else "rodada reiniciada"
            self._set(running=False, step=f"{what} ({summary})", finished_at=time.time())
        except Exception as err:  # noqa: BLE001 - qualquer falha vira mensagem na tela
            self._set(running=False, step="falhou", error=str(err), finished_at=time.time())
            self.hub.set_status(f"reinício falhou: {err}")


RESTARTER: Restarter | None = None


def start(hub: Hub, logs: Path, keep: Callable[[dict], bool], port_forward: int | None = None) -> str:
    """Liga a fonte ao vivo. Com port_forward, mantém um port-forward do observador nessa porta."""
    source = os.environ.get("VIZ_SOURCE", "local")
    if source == "k8s":
        ns = os.environ.get("K8S_NAMESPACE", "maekawa")
        pf = PortForward(ns, port_forward) if port_forward else None
        global RESTARTER
        RESTARTER = Restarter(hub, ns)
        threading.Thread(target=follow_k8s, args=(hub, ns, keep, pf), daemon=True).start()
        return f"k8s (namespace {ns})" + (f", port-forward do observador em :{port_forward}" if pf else "")
    fixed = os.environ.get("LIVE_DIR")
    fixed_path = Path(fixed).resolve() if fixed else None
    threading.Thread(target=follow_local, args=(hub, logs, keep, fixed_path), daemon=True).start()
    return f"local ({fixed_path or 'rodada mais recente em logs/'})"
