#!/bin/sh
# THE SPREAD (specs/core-next.md): the fact reader and the statement reader trained again with other seeds on one
# core — one run is no verdict. `scripts/core-seeds.sh <name> <model dir> <layers> <seeds…>`; the lines appended to
# ~/work/my/okay-desk-private/core-next/<name>/seeds.txt
set -e
cd "$(dirname "$0")/.."
name=$1; dir=$2; layers=$3; shift 3
out=$HOME/work/my/okay-desk-private/core-next/$name
mkdir -p "$out"
export SMALL_DIR="$dir" LOGIC_DIR="$dir" LAYERS="$layers" LOGIC_LAYERS="$layers"
for seed in "$@"; do
  FACT_SEED=$seed sbt -batch "bench/runMain okay.desk.bench.FactReaderBench /tmp/core-seeds-facts.json" 2>&1 |
    sed "s/^\[info\] //" | grep -E "layer fact reader" | sed "s/^/facts seed $seed: /" >> "$out/seeds.txt" || true
  READ_SEED=$seed sbt -batch "bench/runMain okay.desk.bench.StatementRead" 2>&1 | sed "s/^\[info\] //" |
    grep -E "^[A-Z][a-z]+ (en|ru): [0-9]+ texts|no gate" | paste - - | sed "s/^/statements seed $seed: /" >> "$out/seeds.txt" || true
done
cat "$out/seeds.txt"
