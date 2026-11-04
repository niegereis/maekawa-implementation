#!/usr/bin/env bash
# Constrói a imagem linux/arm64 (Ampere A1) e importa no containerd do k3s.
# uso: OCI_HOST=x.x.x.x scripts/oci-load-image.sh
#      IMAGE=maekawa:dev OCI_HOST=x.x.x.x scripts/oci-load-image.sh
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${OCI_HOST:?defina OCI_HOST}"
USER="${OCI_USER:-ubuntu}"
KEY="${OCI_SSH_KEY:-}"
IMAGE="${IMAGE:-maekawa:dev}"

SSH=(ssh -o StrictHostKeyChecking=accept-new)
[ -n "$KEY" ] && SSH+=(-i "$KEY")

echo "== docker build --platform linux/arm64 -t $IMAGE"
docker build --platform linux/arm64 -t "$IMAGE" .

echo "== importando no k3s de $HOST"
docker save "$IMAGE" | gzip | "${SSH[@]}" "$USER@$HOST" 'gunzip | sudo k3s ctr images import -'
"${SSH[@]}" "$USER@$HOST" "sudo k3s ctr images ls | grep -F '$IMAGE' || sudo k3s ctr images ls"

echo "imagem $IMAGE no nó. Com o túnel aberto:"
echo "  export KUBECONFIG=\$PWD/kubeconfig-oci"
echo "  IMAGE=$IMAGE scripts/oci-apply-base.sh"
echo "  IMAGE=$IMAGE scripts/run-experiment.sh normal 1"
