#!/usr/bin/env bash
# Aplica k8s/base no cluster atual (k3s via túnel), sem o Job de clientes,
# e publica o front (viz/) na VM.
# uso: IMAGE=maekawa:dev scripts/oci-apply-base.sh
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="${IMAGE:-maekawa:dev}"

kubectl kustomize k8s/base \
  | sed "s#ghcr.io/OWNER/maekawa:latest#$IMAGE#g" \
  | python3 -c '
import sys, re
docs = [d for d in re.split(r"^---$", sys.stdin.read(), flags=re.M) if d.strip()]
print("\n---\n".join(d for d in docs if not re.search(r"^kind: Job$", d, flags=re.M)))' \
  | kubectl apply -f -

kubectl -n maekawa rollout status statefulset/sync --timeout=180s
kubectl -n maekawa rollout status deployment/observer --timeout=120s
kubectl -n maekawa get pods -o wide

scripts/oci-viz.sh
