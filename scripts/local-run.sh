#!/usr/bin/env bash
# Sobe 7 nos + observador em processos locais (localhost:BASE..BASE+6, observer :BASE+100;
# BASE=7200 por padrao, porque o macOS ocupa a 7000 com o AirPlay Receiver),
# API do observador em localhost:BASE+180 (GET/POST /mode, GET /stats);
# dispara 7 clientes e espera. Serve para testar o transporte TCP sem Kubernetes.
#
# uso: scripts/local-run.sh [ACCESSES] [THINK_MIN_MS] [THINK_MAX_MS] [VARIANT] [SYNC_ENABLED]
#      scripts/local-run.sh --accesses 20 0 0
# sem ACCESSES: modo continuo (padrao), os clientes pedem sem parar ate Ctrl-C.
# ex.: scripts/local-run.sh                  # continuo, sem pausa
#      scripts/local-run.sh 5 0 0            # rodada finita: alta contencao, 5 acessos por cliente
#      scripts/local-run.sh 3 0 0 resolution false   # instrumentacao sem protocolo
set -euo pipefail
cd "$(dirname "$0")/.."

ACCESSES="${ACCESSES:-}"
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --accesses)
      ACCESSES="${2:?--accesses precisa de um inteiro}"
      shift 2 ;;
    --accesses=*)
      ACCESSES="${1#--accesses=}"
      shift ;;
    -h|--help)
      sed -n '2,9p' "$0"
      exit 0 ;;
    --)
      shift
      POS+=("$@")
      break ;;
    -*)
      echo "flag desconhecida: $1 (esperado: --accesses N)" >&2
      exit 1 ;;
    *)
      POS+=("$1")
      shift ;;
  esac
done
n=${#POS[@]}
[ -z "$ACCESSES" ] && [ "$n" -ge 1 ] && ACCESSES="${POS[0]}"
if [ -z "$ACCESSES" ]; then CONTINUOUS=true; ACCESSES=20; else CONTINUOUS=false; fi
THINK_MIN=0;           [ "$n" -ge 2 ] && THINK_MIN="${POS[1]}"
THINK_MAX=0;           [ "$n" -ge 3 ] && THINK_MAX="${POS[2]}"
VARIANT=resolution;    [ "$n" -ge 4 ] && VARIANT="${POS[3]}"
SYNC=true;             [ "$n" -ge 5 ] && SYNC="${POS[4]}"
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

# o observador precisa do mesmo SYNC_ENABLED: ele anuncia o modo da epoca 0 aos nos.
# API em http://localhost:$((BASE + 180))/mode (HTTP_PORT sobrescreve)
ROLE=observer LISTEN_PORT=$((BASE + 100)) SYNC_ENABLED="$SYNC" HTTP_PORT="${HTTP_PORT:-$((BASE + 180))}" java $JVM_OPTS -jar "$JAR" > "$OUT/observer.jsonl" 2>"$OUT/observer.err" &
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
  ROLE=client CLIENT_ID=$i ACCESSES="$ACCESSES" CONTINUOUS="$CONTINUOUS" THINK_MIN_MS="$THINK_MIN" THINK_MAX_MS="$THINK_MAX" \
    REQUEST_TIMEOUT_MS="${REQUEST_TIMEOUT_MS:-60000}" \
    java $JVM_OPTS -jar "$JAR" > "$OUT/client-$i.jsonl" 2>"$OUT/client-$i.err" &
  cpids+=($!)
done
[ "$CONTINUOUS" = true ] && echo "modo continuo: os clientes pedem sem parar; Ctrl-C encerra tudo (logs em $OUT)"
rc=0
for p in "${cpids[@]}"; do wait "$p" || rc=1; done
sleep 0.5
echo "logs em $OUT (clientes rc=$rc)"
exit $rc
