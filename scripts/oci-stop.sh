#!/usr/bin/env bash
# Para a VM Ampere (STOP). Continua Always Free; evita reclaim por ociosidade
# e some da cota de horas se a conta for trial/PAYG.
# uso: scripts/oci-stop.sh          # lê instance_id do terraform
#      OCI_INSTANCE_ID=ocid1.instance... scripts/oci-stop.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ID="${OCI_INSTANCE_ID:-}"
if [ -z "$ID" ] && command -v terraform >/dev/null; then
  ID="$(terraform -chdir=infra/oci output -raw instance_id 2>/dev/null || true)"
fi
if [ -z "$ID" ]; then
  echo "Defina OCI_INSTANCE_ID ou rode terraform apply em infra/oci." >&2
  echo "No console: Compute → Instances → maekawa-k3s → Stop." >&2
  exit 1
fi

if command -v oci >/dev/null; then
  oci compute instance action --instance-id "$ID" --action STOP --wait-for-state STOPPED
else
  echo "OCI CLI não está no PATH. Pare no console: $ID" >&2
  exit 1
fi
