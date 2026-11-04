#!/usr/bin/env bash
# Liga a VM Ampere (START). k3s sobe com systemd.
# uso: scripts/oci-start.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ID="${OCI_INSTANCE_ID:-}"
if [ -z "$ID" ] && command -v terraform >/dev/null; then
  ID="$(terraform -chdir=infra/oci output -raw instance_id 2>/dev/null || true)"
fi
if [ -z "$ID" ]; then
  echo "Defina OCI_INSTANCE_ID ou rode terraform apply em infra/oci." >&2
  echo "No console: Compute → Instances → maekawa-k3s → Start." >&2
  exit 1
fi

if command -v oci >/dev/null; then
  oci compute instance action --instance-id "$ID" --action START --wait-for-state RUNNING
  echo "IP atual:"
  terraform -chdir=infra/oci output -raw public_ip 2>/dev/null || true
  echo "Se o IP público mudou, atualize OCI_HOST e rode scripts/oci-kubeconfig.sh de novo."
else
  echo "OCI CLI não está no PATH. Ligue no console: $ID" >&2
  exit 1
fi
