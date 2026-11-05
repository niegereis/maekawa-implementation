#!/usr/bin/env python3
"""Visualização do Maekawa: ao vivo (padrão) e replay de rodadas gravadas.

uso: python3 viz/server.py [porta]
     scripts/viz.sh                 # local-run.sh: segue logs/ e o observador em :7380
     scripts/viz.sh --k8s           # kind/k3s: kubectl logs -f + observador em :8080 (port-forward)

/api/live é um fluxo SSE com os eventos da rodada em execução (veja live.py).

Abre http://127.0.0.1:8765 — não escuta fora da máquina.
Lê logs/<overlay>/<rep>/*.jsonl (gitignored) e viz/exemplo/.
/api/mode e /api/stats repassam para a API HTTP do observador (OBSERVER_URL,
padrão http://127.0.0.1:7380, a do scripts/local-run.sh). Assim a página fala só
com esta origem e não depende de CORS.
"""
from __future__ import annotations

import json
import os
import queue
import re
import signal
import sys

import live
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent
LOGS = PROJECT / "logs"
# <overlay>/<rep>: rep numérico da suíte ou o timestamp do local-run.sh (20261105-131500)
RUN_ID = re.compile(r"[a-z0-9-]+/[A-Za-z0-9_-]+")
SEND = {"REQUEST", "LOCKED", "FAILED", "INQUIRE", "RELINQUISH", "RELEASE"}
OBS = {"BEGIN", "END", "VIOLATION", "MODE_INIT", "MODE_CHANGE", "MODE_SETTLED", "CS_CHANGE", "COUNTERS_RESET"}
HUB = live.Hub()
SSE_PING_S = 15
OBSERVER_URL = os.environ.get("OBSERVER_URL", "http://127.0.0.1:7380").rstrip("/")
OBSERVER_TIMEOUT_S = 2.0


def keep(event: dict) -> bool:
    ev = event.get("event")
    if event.get("direction") == "send" and ev in SEND:
        return True
    return event.get("process") == "observer" and ev in OBS


def read_run(path: Path) -> list[dict]:
    events: list[dict] = []
    for file in sorted(path.glob("*.jsonl")):
        with file.open(encoding="utf-8") as handle:
            for ordinal, line in enumerate(handle):
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not keep(event):
                    continue
                event["_ord"] = ordinal
                event["_file"] = file.name
                events.append(event)
    events.sort(key=lambda e: (e.get("wall_clock") or "", e["_file"], e["_ord"]))
    for event in events:
        event.pop("_ord", None)
        event.pop("_file", None)
    return events


def observer_call(path: str, body: dict | None = None) -> tuple[int, object]:
    """GET (ou POST com corpo JSON) na API do observador. Devolve (status, json)."""
    data = None if body is None else json.dumps(body).encode()
    req = Request(OBSERVER_URL + path, data=data, method="GET" if body is None else "POST")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urlopen(req, timeout=OBSERVER_TIMEOUT_S) as res:
            return res.status, json.loads(res.read() or b"null")
    except HTTPError as err:
        try:
            return err.code, json.loads(err.read() or b"null")
        except ValueError:
            return err.code, {"error": f"observador respondeu {err.code}"}
    except (URLError, OSError, ValueError) as err:
        reason = getattr(err, "reason", err)
        return 502, {"error": f"observador fora do ar em {OBSERVER_URL}: {reason}", "observer_url": OBSERVER_URL}


def list_runs() -> list[dict]:
    runs = []
    if (ROOT / "exemplo").is_dir():
        runs.append({"id": "exemplo", "label": "exemplo sintético", "synthetic": True})
    if LOGS.is_dir():
        for overlay in sorted(p for p in LOGS.iterdir() if p.is_dir()):
            for rep in sorted(p for p in overlay.iterdir() if p.is_dir()):
                if any(rep.glob("*.jsonl")):
                    runs.append({
                        "id": f"{overlay.name}/{rep.name}",
                        "label": f"{overlay.name} / {rep.name}",
                        "synthetic": False,
                    })
    return runs


def resolve_run(run_id: str) -> Path | None:
    if run_id == "exemplo":
        path = (ROOT / "exemplo").resolve()
        return path if path.is_dir() else None
    if not RUN_ID.fullmatch(run_id or ""):
        return None
    logs = LOGS.resolve()
    path = (LOGS / run_id).resolve()
    if path != logs and not str(path).startswith(str(logs) + os.sep):
        return None
    return path if path.is_dir() else None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        # a página consulta /api/mode e /api/stats a cada segundo: só registra o que não for isso
        if any(p in self.path for p in ("/api/mode", "/api/stats", "/api/live", "/api/info")) and self.command == "GET":
            return
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            self._file(ROOT / "index.html", "text/html; charset=utf-8")
            return
        if parsed.path == "/replay.js":
            self._file(ROOT / "replay.js", "text/javascript; charset=utf-8")
            return
        if parsed.path == "/api/runs":
            self._json(list_runs())
            return
        if parsed.path == "/api/events":
            run_id = (parse_qs(parsed.query).get("run") or [""])[0]
            path = resolve_run(run_id)
            if path is None:
                self._json({"error": "rodada desconhecida"}, 404)
                return
            self._json({"id": run_id, "events": read_run(path)})
            return
        if parsed.path == "/api/live":
            self._live()
            return
        if parsed.path == "/api/info":
            r = live.RESTARTER
            self._json({"source": os.environ.get("VIZ_SOURCE", "local"),
                        "restart": dict(r.state) if r else None,
                        "clients": dict(live.CLIENTS) if r else None})
            return
        if parsed.path == "/api/mode":
            self._proxy("/mode")
            return
        if parsed.path == "/api/stats":
            self._proxy("/stats")
            return
        self._json({"error": "não encontrado"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path not in ("/api/mode", "/api/reset", "/api/restart", "/api/stop-clients"):
            self._json({"error": "não encontrado"}, 404)
            return
        # Só JSON: um formulário de outro site não consegue mandar este Content-Type
        # sem preflight, e este servidor não responde a preflight.
        if (self.headers.get("Content-Type") or "").split(";")[0].strip() != "application/json":
            self._json({"error": "use Content-Type: application/json"}, 415)
            return
        if path == "/api/reset":
            self._proxy("/reset", {})
            return
        if path in ("/api/restart", "/api/stop-clients"):
            r = live.RESTARTER
            if r is None:
                self._json({"error": "só existe no modo --k8s; no local, rode o local-run.sh de novo"}, 400)
                return
            if path == "/api/stop-clients":
                ok = r.stop_clients()
            else:
                length = int(self.headers.get("Content-Length") or 0)
                try:
                    body = json.loads(self.rfile.read(length)) if 0 < length <= 4096 else {}
                except ValueError:
                    body = {}
                ok = r.start(continuous=isinstance(body, dict) and body.get("continuous") is True)
            if not ok:
                self._json({"error": "já há uma operação em andamento", "restart": dict(r.state)}, 409)
                return
            self._json({"restart": dict(r.state)}, 202)
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 4096:
            self._json({"error": "corpo vazio ou grande demais"}, 400)
            return
        try:
            body = json.loads(self.rfile.read(length))
        except ValueError:
            self._json({"error": "corpo não é JSON"}, 400)
            return
        if not isinstance(body, dict):
            self._json({"error": "corpo deve ser um objeto JSON"}, 400)
            return
        change: dict = {}
        if "sync_enabled" in body:
            if not isinstance(body["sync_enabled"], bool):
                self._json({"error": "sync_enabled deve ser true ou false"}, 400)
                return
            change["sync_enabled"] = body["sync_enabled"]
        if "cs_min_ms" in body or "cs_max_ms" in body:
            lo, hi = body.get("cs_min_ms"), body.get("cs_max_ms")
            if not (isinstance(lo, int) and isinstance(hi, int)) or isinstance(lo, bool) or isinstance(hi, bool):
                self._json({"error": "cs_min_ms e cs_max_ms devem ir juntos, em ms inteiros"}, 400)
                return
            change["cs_min_ms"], change["cs_max_ms"] = lo, hi
        if not change:
            self._json({"error": "informe sync_enabled e/ou cs_min_ms + cs_max_ms"}, 400)
            return
        self._proxy("/mode", change)

    def _live(self) -> None:
        """SSE: snapshot da rodada atual e depois um evento por mensagem."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        q = HUB.subscribe()
        try:
            while True:
                try:
                    msg = q.get(timeout=SSE_PING_S)
                    self.wfile.write(b"data: " + json.dumps(msg, ensure_ascii=False).encode() + b"\n\n")
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            HUB.unsubscribe(q)

    def _proxy(self, path: str, body: dict | None = None) -> None:
        status, payload = observer_call(path, body)
        self._json(payload, status)

    def _file(self, path: Path, content_type: str) -> None:
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _json(self, body: object, status: int = 200) -> None:
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # para o finally rodar no kill
    pf_port = int(os.environ["VIZ_PORT_FORWARD"]) if os.environ.get("VIZ_PORT_FORWARD") else None
    source = live.start(HUB, LOGS, keep, pf_port)
    print(f"viz em http://127.0.0.1:{port} · ao vivo: {source} · observador em {OBSERVER_URL}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        live.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
