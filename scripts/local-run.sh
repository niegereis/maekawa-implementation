#!/usr/bin/env bash
# Sobe 7 nos + observador em processos locais (localhost:BASE..BASE+6, observer :BASE+100;
# BASE=7200 por padrao, porque o macOS ocupa a 7000 com o AirPlay Receiver),
# dispara 7 clientes e espera. Serve para testar o transporte TCP sem Kubernetes.
#
# uso: scripts/local-run.sh [ACCESSES] [THINK_MIN_MS] [THINK_MAX_MS] [VARIANT] [SYNC_ENABLED]
# ex.: scripts/local-run.sh 5 0 0            # alta contencao, 5 acessos por cliente
#      scripts/local-run.sh 3 0 0 resolution false   # instrumentacao sem protocolo
set -euo pipefail
cd "$(dirname "$0")/.."

ACCESSES="${1:-5}"
THINK_MIN="${2:-0}"
THINK_MAX="${3:-0}"
VARIANT="${4:-resolution}"
SYNC="${5:-true}"
JAR=target/maekawa.jar
OUT="${OUT:-logs/local/$(date +%Y%m%d-%H%M%S)}"
JVM_OPTS="-XX:+UseSerialGC -Xmx96m -XX:TieredStopAtLevel=1"
BASE="${BASE:-7200}"

[ -f "$JAR" ] || { echo "faltou $JAR; rode: mvn -q -DskipTests package"; exit 1; }
mkdir -p "$OUT"

export PEER_ADDR_PATTERN="localhost:$((BASE / 100))%02d"
export OBSERVER_ADDR="localhost:$((BASE + 100))"
pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

ROLE=observer LISTEN_PORT=$((BASE + 100)) java $JVM_OPTS -jar "$JAR" > "$OUT/observer.jsonl" 2>"$OUT/observer.err" &
pids+=($!)
for i in 0 1 2 3 4 5 6; do
  ROLE=node PROCESS_ID=$i LISTEN_PORT=$((BASE + i)) VARIANT="$VARIANT" SYNC_ENABLED="$SYNC" \
    CS_MIN_MS="${CS_MIN_MS:-200}" CS_MAX_MS="${CS_MAX_MS:-1000}" \
    java $JVM_OPTS -jar "$JAR" > "$OUT/sync-$i.jsonl" 2>"$OUT/sync-$i.err" &
  pids+=($!)
done
sleep 1.5

cpids=()
for i in 0 1 2 3 4 5 6; do
  ROLE=client CLIENT_ID=$i ACCESSES="$ACCESSES" THINK_MIN_MS="$THINK_MIN" THINK_MAX_MS="$THINK_MAX" \
    REQUEST_TIMEOUT_MS="${REQUEST_TIMEOUT_MS:-60000}" \
    java $JVM_OPTS -jar "$JAR" > "$OUT/client-$i.jsonl" 2>"$OUT/client-$i.err" &
  cpids+=($!)
done
rc=0
for p in "${cpids[@]}"; do wait "$p" || rc=1; done
sleep 0.5
echo "logs em $OUT (clientes rc=$rc)"
exit $rc
