#!/usr/bin/env bash
# Sobe Grafana + Loki + Promtail no cluster atual (kind ou k3s).
# Namespace observability: o run-experiment.sh não mexe nele.
# Nada é LoadBalancer. O Grafana só abre pelo port-forward.
#
# uso: scripts/obs-up.sh
#      kubectl -n observability port-forward svc/grafana 3000:3000
set -euo pipefail
cd "$(dirname "$0")/.."

kubectl apply -k k8s/observability
kubectl -n observability rollout status deployment/loki --timeout=180s
kubectl -n observability rollout status deployment/grafana --timeout=180s
kubectl -n observability rollout status daemonset/promtail --timeout=180s

echo
echo "Grafana: admin / maekawa  (só neste port-forward, não está na internet)"
echo "  kubectl -n observability port-forward svc/grafana 3000:3000"
echo "  http://127.0.0.1:3000  → pasta Maekawa"
echo
echo "Os 7 nós (replay, com pausa) ficam na sua máquina:"
echo "  scripts/viz.sh"
echo "  http://127.0.0.1:8765"
