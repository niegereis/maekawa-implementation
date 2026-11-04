#!/usr/bin/env bash
# Suite oficial no cluster atual (kind ou k3s na Oracle): 1 sem-protocolo, 1 deadlock,
# 3 carga normal, 3 alta contenção, 1 desigual. Copia os report.json para evidence/.
#
# uso: IMAGE=maekawa:dev scripts/run-suite.sh
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="${IMAGE:-}"
export IMAGE

fail=0
run() {
  local overlay=$1 rep=$2
  echo ""
  echo "######## $overlay / $rep ########"
  if ! scripts/run-experiment.sh "$overlay" "$rep"; then
    echo "FALHOU: $overlay/$rep" >&2
    fail=1
  fi
}

run sem-protocolo 1
run deadlock 1
for r in 1 2 3; do run normal $r; done
for r in 1 2 3; do run alta-contencao $r; done
run desigual 1

mkdir -p evidence
{
  echo "# Evidência das rodadas"
  echo
  echo "Gerado em $(date -u +%Y-%m-%dT%H:%M:%SZ). Cada pasta é o \`report.json\` da rodada."
  echo "Os JSON Lines completos ficam em \`logs/<overlay>/<rep>/\` (gitignored)."
  echo
} > evidence/README.md

for overlay in sem-protocolo deadlock normal alta-contencao desigual; do
  for rep in 1 2 3; do
    src="logs/$overlay/$rep/report.json"
    [ -f "$src" ] || continue
    mkdir -p "evidence/$overlay/$rep"
    cp "$src" "evidence/$overlay/$rep/report.json"
    [ -f "logs/$overlay/$rep/run.json" ] && cp "logs/$overlay/$rep/run.json" "evidence/$overlay/$rep/run.json"
  done
done

python3 - <<'PY'
import json, pathlib
rows = []
for p in sorted(pathlib.Path("evidence").glob("*/*/report.json")):
    r = json.loads(p.read_text())
    o, n = p.parts[1], p.parts[2]
    obs = r["observer"]
    req = r["requests"]
    rows.append((o, n, obs["violations"], obs["max_active"], req["committed"], req["requested"],
                 r.get("protocol_per_entry"), (r.get("acquire_wait_ms") or {}).get("median")))
print(f"{'overlay':<16} {'rep':<4} {'viol':>5} {'maxR':>5} {'ok/ped':>8} {'msg/e':>6} {'wait_med':>9}")
for o,n,v,m,c,q,pe,w in rows:
    print(f"{o:<16} {n:<4} {v:>5} {m:>5} {c:>3}/{q:<3} {str(pe):>6} {str(w):>9}")
PY

exit $fail
