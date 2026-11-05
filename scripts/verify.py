#!/usr/bin/env python3
"""Verificador das propriedades a partir dos logs de uma rodada.

uso: scripts/verify.py logs/<overlay>/<rep> [--expect normal|sem-protocolo|deadlock]

Le observer.jsonl, sync-*.jsonl e client-*.jsonl, imprime as metricas da secao 8 do plano e
falha (exit 1) se a rodada nao cumpriu o que se esperava dela:
  normal / alta-contencao / desigual : zero sobreposicao e todo request_id com COMMITTED
  sem-protocolo                      : pelo menos uma VIOLATION (a instrumentacao enxerga)
  deadlock                           : nenhum COMMITTED e zero sobreposicao (travou, mas sem violar)
"""
import glob
import json
import os
import statistics
import sys
from collections import Counter, defaultdict

PROTOCOL = {"REQUEST", "LOCKED", "RELEASE", "FAILED", "INQUIRE", "RELINQUISH"}


def read(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


def rid(e):
    r = e.get("request_id")
    if r is None:
        return None
    return (r["client_id"], r["seq"])


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    out = sys.argv[1].rstrip("/")
    expect = None
    if "--expect" in sys.argv:
        expect = sys.argv[sys.argv.index("--expect") + 1]
    else:
        run = read(os.path.join(out, "run.json"))
        overlay = run[0]["overlay"] if run else os.path.basename(os.path.dirname(out))
        expect = overlay if overlay in ("sem-protocolo", "deadlock") else "normal"

    observer = read(os.path.join(out, "observer.jsonl"))
    nodes = {p: read(p) for p in sorted(glob.glob(os.path.join(out, "sync-*.jsonl")))}
    clients = {p: read(p) for p in sorted(glob.glob(os.path.join(out, "client-*.jsonl")))}

    # --- observador
    violations = sum(1 for e in observer if e.get("event") == "VIOLATION")
    begins = sum(1 for e in observer if e.get("event") == "BEGIN")
    ends = sum(1 for e in observer if e.get("event") == "END")
    max_active = max((e.get("active", 0) for e in observer if e.get("event") == "BEGIN"), default=0)

    # --- clientes
    committed_ids, timed_out_ids, requested_ids = set(), set(), set()
    per_client = {}
    for path, evs in clients.items():
        name = os.path.basename(path).replace(".jsonl", "")
        c = t = r = 0
        for e in evs:
            if e.get("event") == "ACQUIRE_REQ" and e.get("direction") == "send":
                requested_ids.add(rid(e)); r += 1
            elif e.get("event") == "COMMITTED" and e.get("kind") == "local":
                committed_ids.add(rid(e)); c += 1
            elif e.get("event") == "TIMEOUT":
                timed_out_ids.add(rid(e)); t += 1
        per_client[name] = {"requested": r, "committed": c, "timed_out": t}
    missing = requested_ids - committed_ids

    # --- nos: mensagens e espera
    sent = Counter()
    waits_ms = []
    entries = 0
    for path, evs in nodes.items():
        t_req = {}
        for e in evs:
            ev = e.get("event")
            if e.get("direction") == "send" and ev in PROTOCOL:
                sent[ev] += 1
            if ev == "REQUEST_SENT":
                t_req[rid(e)] = e["ts_monotonic_ns"]
            elif ev == "ACQUIRE" and rid(e) in t_req:
                waits_ms.append((e["ts_monotonic_ns"] - t_req[rid(e)]) / 1e6)
            elif ev == "ENTER":
                entries += 1
    protocol_total = sum(sent.values())

    # --- configuracao: o que cada processo registrou no START (N, K, secao critica, pausa, semente)
    def start_of(evs):
        return next((e for e in evs if e.get("event") == "START"), {})
    node_starts = {os.path.basename(p).replace(".jsonl", ""): start_of(evs) for p, evs in nodes.items()}
    client_starts = {os.path.basename(p).replace(".jsonl", ""): start_of(evs) for p, evs in clients.items()}
    any_node = next((s for s in node_starts.values() if s), {})
    any_client = next((s for s in client_starts.values() if s), {})
    k = len(any_node.get("voters") or []) or None
    n_nodes = any_node.get("n_nodes") or len(nodes)
    config = {
        "nodes": n_nodes,
        "clients": len(clients),
        "k": k,
        "variant": any_node.get("variant"),
        "cs_ms": [any_node.get("cs_min_ms"), any_node.get("cs_max_ms")],
        "think_ms": [any_client.get("think_min_ms"), any_client.get("think_max_ms")],
        "accesses_per_client": any_client.get("accesses"),
        "seeds": {name: s.get("seed") for name, s in {**node_starts, **client_starts}.items()},
    }

    report = {
        "expect": expect,
        "config": config,
        "observer": {"begins": begins, "ends": ends, "max_active": max_active, "violations": violations},
        "clients": per_client,
        "requests": {"requested": len(requested_ids), "committed": len(committed_ids),
                     "timed_out": len(timed_out_ids), "missing_committed": sorted(missing)},
        "protocol_messages": dict(sorted(sent.items())),
        "protocol_total": protocol_total,
        "entries": entries,
        "protocol_per_entry": round(protocol_total / entries, 2) if entries else None,
        "acquire_wait_ms": {
            "n": len(waits_ms),
            "median": round(statistics.median(waits_ms), 1) if waits_ms else None,
            "p90": round(sorted(waits_ms)[int(0.9 * (len(waits_ms) - 1))], 1) if waits_ms else None,
            "max": round(max(waits_ms), 1) if waits_ms else None,
        },
    }
    print(json.dumps(report, indent=2, ensure_ascii=False))
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    problems = []
    if expect == "sem-protocolo":
        if violations == 0:
            problems.append("rodada sem protocolo NAO gerou violacao: o observador nao esta enxergando sobreposicao")
    elif expect == "deadlock":
        if committed_ids:
            problems.append(f"cenario de deadlock teve {len(committed_ids)} COMMITTED; a versao basica deveria travar")
        if violations:
            problems.append(f"{violations} violacao(oes) de exclusao mutua")
    else:
        if violations:
            problems.append(f"{violations} violacao(oes) de exclusao mutua")
        if missing:
            problems.append(f"{len(missing)} request_id sem COMMITTED: {sorted(missing)[:5]}...")
        if begins != ends:
            problems.append(f"BEGIN ({begins}) != END ({ends})")
        if not requested_ids:
            problems.append("nenhum pedido encontrado nos logs de cliente")

    if problems:
        print("\nFALHOU:")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print("\nOK: rodada cumpriu o esperado para", expect)


if __name__ == "__main__":
    main()
