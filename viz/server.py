#!/usr/bin/env python3
"""Replay local dos logs do Maekawa.

uso: python3 viz/server.py [porta]
     scripts/viz.sh

Abre http://127.0.0.1:8765 — não escuta fora da máquina.
Lê logs/<overlay>/<rep>/*.jsonl (gitignored) e viz/exemplo/.
"""
from __future__ import annotations

import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent
LOGS = PROJECT / "logs"
RUN_ID = re.compile(r"[a-z0-9-]+/[0-9]+")
SEND = {"REQUEST", "LOCKED", "FAILED", "INQUIRE", "RELINQUISH", "RELEASE"}
OBS = {"BEGIN", "END", "VIOLATION"}


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
        self._json({"error": "não encontrado"}, 404)

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
    print(f"replay em http://127.0.0.1:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
