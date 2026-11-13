#!/bin/sh
# THE MEASURE OF specs/core-joint.md §3: an adapter trained through the heads, exported to the page's format, and the
# reader trained afresh over the adapted core by the page's recipe, seeds 1–3 — against heads alone (e2b-{1,2,3}).
# `scripts/joint-measure.sh <name> <adapter (mlx LoRA)>`; logs in ~/work/my/okay-desk-private/joint/<name>-<seed>.txt
set -e
cd "$(dirname "$0")/.."
P=$HOME/work/my/okay-desk-private
L=$HOME/work/my/mlx-scala/native-lib/out/lib
name=$1; lora=$2
page=$P/joint/$name.page.safetensors
[ -f "$page" ] || sbt -batch "bench/runMain okay.desk.bench.SemanticExport $lora $page" > /dev/null 2>&1
for seed in ${SEEDS:-1 2 3}; do
  save=""; [ $seed = 1 ] && save=$P/joint/$name.reader.bin
  # (READ_SAVE only for seed 1: an empty one is a path to a directory, and the run fails at its end)
  env ${save:+READ_SAVE=$save} SEMANTIC_NATIVE_ADAPTER=$page MLX_SCALA_LIB=$L READ_ECHO=2 READ_SEED=$seed \
    READ_TRAIN=4000 READ_TEACHER=$P/teacher/all.jsonl READ_TEACHER_TEST=$P/teacher/test.jsonl \
    sbt -batch 'set bench / javaOptions += "-Xmx26g"' "bench/runMain okay.desk.bench.StatementRead" \
    > $P/joint/$name-$seed.txt 2>&1 || echo "seed $seed FAILED" >> $P/joint/$name.status
done
date > $P/joint/$name.done
