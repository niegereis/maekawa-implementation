#!/usr/bin/env bash
# Cria (ou reaproveita) um cluster kind local, constroi a imagem, carrega no cluster e aplica k8s/base,
# inclusive o Job de clientes: com o ConfigMap base (CONTINUOUS=true) eles pedem sem parar, e a tela
# (scripts/viz.sh --k8s) ja abre com movimento. As rodadas oficiais continuam no run-experiment.sh.
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

# aplica a base com a imagem local (imagePullPolicy ja e IfNotPresent). Primeiro sem o Job, para os
# nos e o observador estarem de pe; depois o Job (o spec de um Job nao muda: apaga e cria de novo).
BASE_YAML="$(mktemp)"
trap 'rm -f "$BASE_YAML"' EXIT
kubectl kustomize k8s/base | sed "s#ghcr.io/OWNER/maekawa:latest#$IMAGE#g" > "$BASE_YAML"
split_docs() {  # $1 = job | infra
  python3 -c '
import sys, re
docs = [d for d in re.split(r"^---$", open(sys.argv[2]).read(), flags=re.M) if d.strip()]
is_job = lambda d: re.search(r"^kind: Job$", d, flags=re.M) is not None
print("\n---\n".join(d for d in docs if is_job(d) == (sys.argv[1] == "job")))' "$1" "$BASE_YAML"
}
split_docs infra | kubectl apply -f -
# recria nos e observador: pegam a imagem recem-carregada (mesma tag) e o ConfigMap base, mesmo
# que a ultima rodada tenha sido outro overlay (ex.: deadlock com VARIANT=basic e GATE_HOLD)
kubectl -n maekawa delete pod -l app=sync --wait=true >/dev/null
kubectl -n maekawa delete pod -l app=observer --wait=true >/dev/null

kubectl -n maekawa rollout status statefulset/sync --timeout=180s
kubectl -n maekawa rollout status deployment/observer --timeout=120s

kubectl -n maekawa delete job clients --ignore-not-found --wait=true >/dev/null
split_docs job | kubectl apply -f -
echo "clientes em modo continuo (CONTINUOUS=true no ConfigMap base). Parar: kubectl -n maekawa delete job clients"
kubectl -n maekawa get pods -o wide
