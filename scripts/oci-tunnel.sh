#!/usr/bin/env bash
# Túnel SSH do API server do k3s. Deixa 6443 só no localhost.
# uso: OCI_HOST=x.x.x.x scripts/oci-tunnel.sh
set -euo pipefail
HOST="${OCI_HOST:?defina OCI_HOST}"
USER="${OCI_USER:-ubuntu}"
KEY="${OCI_SSH_KEY:-}"
PORT="${OCI_TUNNEL_PORT:-6443}"

SSH=(ssh -N -L "${PORT}:127.0.0.1:6443" -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new)
[ -n "$KEY" ] && SSH+=(-i "$KEY")

echo "túnel 127.0.0.1:$PORT -> $USER@$HOST:6443  (Ctrl+C para fechar)"
exec "${SSH[@]}" "$USER@$HOST"
