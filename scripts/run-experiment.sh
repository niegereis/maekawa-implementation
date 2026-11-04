#!/usr/bin/env bash
# Executa uma rodada: aplica o overlay, reinicia os nos e o observador (estado limpo),
# espera o Job de clientes, recolhe os logs em logs/<overlay>/<rep>/ e roda o verificador.
#
# uso: scripts/run-experiment.sh <overlay> [rep]      overlay em k8s/overlays/
# ex.: scripts/run-experiment.sh alta-contencao 1
#      IMAGE=maekawa:dev scripts/run-experiment.sh deadlock 1     # no kind, com a imagem local
set -euo pipefail
cd "$(dirname "$0")/.."

OVERLAY="${1:?overlay (sem-protocolo|deadlock|normal|alta-contencao|desigual)}"
REP="${2:-1}"
NS=maekawa
IMAGE="${IMAGE:-}"                       # vazio = imagem do manifest (ghcr); no kind: IMAGE=maekawa:dev
JOB_TIMEOUT="${JOB_TIMEOUT:-30m}"
OUT="logs/$OVERLAY/$REP"
mkdir -p "$OUT"

echo "== $OVERLAY rep $REP"
kubectl kustomize "k8s/overlays/$OVERLAY" \
  | { if [ -n "$IMAGE" ]; then sed "s#ghcr.io/OWNER/maekawa:latest#$IMAGE#g"; else cat; fi; } \
  > "$OUT/manifests.yaml"

# separa o Job do resto: primeiro sobe/atualiza infra, depois dispara a carga
python3 - "$OUT/manifests.yaml" "$OUT" <<'EOF'
import sys, re
text = open(sys.argv[1]).read()
docs = [d for d in re.split(r'^---$', text, flags=re.M) if d.strip()]
is_job = lambda d: re.search(r'^kind: Job$', d, flags=re.M) is not None
open(sys.argv[2] + '/infra.yaml', 'w').write('\n---\n'.join(d for d in docs if not is_job(d)))
open(sys.argv[2] + '/job.yaml', 'w').write('\n---\n'.join(d for d in docs if is_job(d)))
EOF

kubectl -n $NS delete job clients --ignore-not-found --wait=true >/dev/null
kubectl apply -f "$OUT/infra.yaml"
# reinicia para zerar estado dos nos, relogios e contadores do observador.
# Apagar os pods (em vez de rollout restart) recria os 7 em paralelo: ~10 s em vez de ~45 s.
kubectl -n $NS delete pod -l app=sync --wait=true
kubectl -n $NS delete pod -l app=observer --wait=true
kubectl -n $NS rollout status statefulset/sync --timeout=180s
kubectl -n $NS rollout status deployment/observer --timeout=120s
sleep 2

START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
kubectl apply -f "$OUT/job.yaml"
echo "job iniciado em $START; esperando ate $JOB_TIMEOUT (termina em Complete ou Failed)"
# kubectl wait so aceita uma condicao; o overlay deadlock termina em Failed de proposito, entao fazemos poll
deadline=$(( $(date +%s) + $(python3 -c "import re,sys;t=sys.argv[1];m=re.fullmatch(r'(\d+)([smh]?)',t);print(int(m[1])*{'':1,'s':1,'m':60,'h':3600}[m[2]])" "$JOB_TIMEOUT") ))
JOB_RC=124
while [ "$(date +%s)" -lt "$deadline" ]; do
  st=$(kubectl -n $NS get job clients -o jsonpath='{range .status.conditions[*]}{.type}={.status} {end}' 2>/dev/null || true)
  case "$st" in
    *Complete=True*) JOB_RC=0; break ;;
    *Failed=True*)   JOB_RC=1; break ;;
  esac
  sleep 3
done
[ $JOB_RC -eq 124 ] && echo "aviso: Job nao terminou em $JOB_TIMEOUT; recolhendo logs mesmo assim"
END=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# recolhe logs
for i in 0 1 2 3 4 5 6; do
  kubectl -n $NS logs "sync-$i" > "$OUT/sync-$i.jsonl" || true
done
kubectl -n $NS logs deployment/observer > "$OUT/observer.jsonl" || true
for pod in $(kubectl -n $NS get pods -l job-name=clients -o name); do
  idx=$(kubectl -n $NS get "$pod" -o jsonpath='{.metadata.annotations.batch\.kubernetes\.io/job-completion-index}')
  kubectl -n $NS logs "$pod" > "$OUT/client-$idx.jsonl" || true
done
kubectl -n $NS get pods -o wide > "$OUT/pods.txt"
cat > "$OUT/run.json" <<EOF
{"overlay":"$OVERLAY","rep":$REP,"start":"$START","end":"$END","job_rc":$JOB_RC,"image":"${IMAGE:-manifest}"}
EOF
rm -f "$OUT/infra.yaml" "$OUT/job.yaml"

echo "logs em $OUT (job rc=$JOB_RC)"
python3 scripts/verify.py "$OUT"
