#!/usr/bin/env bash
# Copia o kubeconfig do k3s e reescreve o server para o túnel local (127.0.0.1:6443).
# uso: OCI_HOST=x.x.x.x scripts/oci-kubeconfig.sh
#      OCI_SSH_KEY=~/.ssh/id_ed25519 OCI_USER=ubuntu scripts/oci-kubeconfig.sh
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${OCI_HOST:?defina OCI_HOST (IP público da VM)}"
USER="${OCI_USER:-ubuntu}"
KEY="${OCI_SSH_KEY:-}"
OUT="${KUBECONFIG:-$PWD/kubeconfig-oci}"

SSH=(ssh -o StrictHostKeyChecking=accept-new)
SCP=(scp -o StrictHostKeyChecking=accept-new)
if [ -n "$KEY" ]; then
  SSH+=(-i "$KEY")
  SCP+=(-i "$KEY")
fi

echo "== esperando SSH em $USER@$HOST"
for i in $(seq 1 30); do
  if "${SSH[@]}" -o ConnectTimeout=5 "$USER@$HOST" 'echo ok' >/dev/null 2>&1; then
    break
  fi
  sleep 5
  if [ "$i" -eq 30 ]; then
    echo "SSH não respondeu. Confira o IP, a chave e o NSG (porta 22)." >&2
    exit 1
  fi
done

echo "== esperando k3s"
"${SSH[@]}" "$USER@$HOST" 'sudo test -f /etc/rancher/k3s/k3s.yaml'
for i in $(seq 1 36); do
  if "${SSH[@]}" "$USER@$HOST" 'sudo k3s kubectl get nodes >/dev/null 2>&1'; then
    break
  fi
  sleep 5
  if [ "$i" -eq 36 ]; then
    echo "k3s não ficou Ready. Veja: ssh $USER@$HOST 'sudo journalctl -u k3s -n 80'" >&2
    exit 1
  fi
done

"${SSH[@]}" "$USER@$HOST" 'sudo cat /etc/rancher/k3s/k3s.yaml' \
  | sed -e 's#https://127.0.0.1:6443#https://127.0.0.1:6443#' \
        -e 's#https://[0-9.]*:6443#https://127.0.0.1:6443#' \
  > "$OUT"
chmod 600 "$OUT"

echo "kubeconfig em $OUT"
echo "Abra o túnel noutro terminal:"
echo "  OCI_HOST=$HOST scripts/oci-tunnel.sh"
echo "Depois:"
echo "  export KUBECONFIG=$OUT"
echo "  kubectl get nodes"
