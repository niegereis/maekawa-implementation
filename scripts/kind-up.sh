#!/usr/bin/env bash
# Cria (ou reaproveita) um cluster kind local, constroi a imagem, carrega no cluster e aplica k8s/base.
# uso: scripts/kind-up.sh [nome-do-cluster]
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${1:-maekawa}"
IMAGE="${IMAGE:-maekawa:dev}"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

docker build -t "$IMAGE" .
kind load docker-image "$IMAGE" --name "$CLUSTER"

# aplica a base com a imagem local (imagePullPolicy ja e IfNotPresent), sem o Job:
# a carga e disparada por scripts/run-experiment.sh
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
