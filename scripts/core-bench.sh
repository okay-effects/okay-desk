#!/bin/sh
# THE COMPARISON OF CORES (specs/core-next.md): every measure the page depends on, for one model directory —
# `scripts/core-bench.sh <name> <model dir> <layers>`; the logs and the readers trained on it in
# ~/work/my/okay-desk-private/core-next/<name>/, the summary lines in its summary.txt. One sbt, one JVM a bench.
set -e
cd "$(dirname "$0")/.."
name=$1; dir=$2; layers=$3
out=$HOME/work/my/okay-desk-private/core-next/$name
mkdir -p "$out"
export SMALL_DIR="$dir" LOGIC_DIR="$dir" LAYERS="$layers" LOGIC_LAYERS="$layers" FACTS_FILE="$out/facts.json"
sbt -batch "bench/runMain okay.desk.bench.CoreBench $dir $layers" > "$out/core.log" 2>&1 || echo "CoreBench FAILED" >> "$out/core.log"
sbt -batch "bench/runMain okay.desk.bench.FactReaderBench $out/facts.json" > "$out/facts.log" 2>&1 || echo "FactReaderBench FAILED" >> "$out/facts.log"
sbt -batch "bench/runMain okay.desk.bench.RelationsBench $out/relations.json" > "$out/relations.log" 2>&1 || echo "RelationsBench FAILED" >> "$out/relations.log"
READ_SAVE="$out/statement.reader" sbt -batch "bench/runMain okay.desk.bench.StatementRead" > "$out/statement.log" 2>&1 || echo "StatementRead FAILED" >> "$out/statement.log"
{
  echo "# $name: $dir ($layers layers)"
  for f in core facts relations statement; do sed "s/^\[info\] //" "$out/$f.log"; done |
    grep -E "MB of weights|^contract|^sample|layer fact reader|post-development|^relations:|^[A-Z][a-z]+ (en|ru): [0-9]+ texts|no gate|FAILED" || true
} > "$out/summary.txt"
cat "$out/summary.txt"
