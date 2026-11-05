#!/usr/bin/env bash
# Replay dos 7 nós a partir dos JSON Lines. Só em 127.0.0.1.
# uso: scripts/viz.sh [porta]
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${1:-8765}"
exec python3 viz/server.py "$PORT"
