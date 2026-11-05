#!/usr/bin/env bash
# Visualização dos 7 nós: ao vivo (padrão) e replay das rodadas gravadas. Só em 127.0.0.1.
#
# uso: scripts/viz.sh [--k8s] [porta]
#   sem flag  segue a rodada mais recente em logs/ (scripts/local-run.sh) e o observador em
#             http://127.0.0.1:7380
#   --k8s     segue `kubectl logs -f` dos pods de maekawa e mantém sozinho um port-forward do
#             observador em 127.0.0.1:18080, refeito a cada rodada (o pod do observador é recriado)
# OBSERVER_URL (desliga o port-forward próprio), LIVE_DIR e K8S_NAMESPACE sobrescrevem os padrões.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${1:-}" = "--k8s" ]; then
  shift
  export VIZ_SOURCE=k8s
  if [ -z "${OBSERVER_URL:-}" ]; then
    export VIZ_PORT_FORWARD=18080
    export OBSERVER_URL="http://127.0.0.1:18080"
  fi
fi
PORT="${1:-8765}"
export PYTHONDONTWRITEBYTECODE=1
exec python3 viz/server.py "$PORT"
