#!/usr/bin/env bash
# Copia o front (viz/) e k8s/base para a VM e reinicia o serviço maekawa-viz.
# O processo escuta só em 127.0.0.1:8765 na VM. Neste Mac:
#   ssh -N -L 8766:127.0.0.1:8765 ubuntu@$OCI_HOST
#   http://127.0.0.1:8766
# uso: OCI_HOST=x.x.x.x scripts/oci-viz.sh
#      scripts/oci-viz.sh          # OCI_HOST via terraform output, se existir
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${OCI_HOST:-}"
if [ -z "$HOST" ] && command -v terraform >/dev/null; then
  HOST="$(terraform -chdir=infra/oci output -raw public_ip 2>/dev/null || true)"
fi
if [ -z "$HOST" ]; then
  echo "Defina OCI_HOST (IP público da VM)." >&2
  exit 1
fi

USER="${OCI_USER:-ubuntu}"
KEY="${OCI_SSH_KEY:-}"
DEST="${OCI_VIZ_DIR:-/home/$USER/maekawa}"

SSH=(ssh -o StrictHostKeyChecking=accept-new)
RSH="ssh -o StrictHostKeyChecking=accept-new"
if [ -n "$KEY" ]; then
  SSH+=(-i "$KEY")
  RSH+=" -i $KEY"
fi

echo "== front em $USER@$HOST:$DEST"
"${SSH[@]}" "$USER@$HOST" "mkdir -p '$DEST/viz' '$DEST/k8s/base'"
rsync -az --delete -e "$RSH" viz/ "$USER@$HOST:$DEST/viz/"
rsync -az --delete -e "$RSH" k8s/base/ "$USER@$HOST:$DEST/k8s/base/"

"${SSH[@]}" "$USER@$HOST" "sudo cp /etc/rancher/k3s/k3s.yaml '$DEST/kubeconfig' && sudo chown '$USER:$USER' '$DEST/kubeconfig' && chmod 600 '$DEST/kubeconfig'"

"${SSH[@]}" "$USER@$HOST" "sudo tee /etc/systemd/system/maekawa-viz.service >/dev/null" <<EOF
[Unit]
Description=Front do Maekawa (viz)
After=k3s.service
Wants=k3s.service

[Service]
User=$USER
WorkingDirectory=$DEST
Environment=KUBECONFIG=$DEST/kubeconfig
Environment=VIZ_SOURCE=k8s
Environment=VIZ_PORT_FORWARD=18080
Environment=OBSERVER_URL=http://127.0.0.1:18080
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/usr/bin/python3 $DEST/viz/server.py 8765
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF

"${SSH[@]}" "$USER@$HOST" "sudo systemctl daemon-reload && sudo systemctl enable maekawa-viz && sudo systemctl restart maekawa-viz"
"${SSH[@]}" "$USER@$HOST" "for i in 1 2 3 4 5; do systemctl is-active maekawa-viz && curl -fsS -o /dev/null http://127.0.0.1:8765/ && exit 0; sleep 1; done; journalctl -u maekawa-viz -n 30 --no-pager; exit 1"

echo "front na VM em 127.0.0.1:8765. Neste Mac:"
echo "  ssh -N -L 8766:127.0.0.1:8765 $USER@$HOST"
echo "  http://127.0.0.1:8766"
