#!/bin/sh
# ONE TURN OF THE CYCLE (docs/toolkit.md §2): heads on half the corpus → the adapter trained through them on the other
# half and the teacher's rewrites → the reader by the page's recipe over the adapted core, seeds 1–3 → rows of the
# history. Each step leaves its file and is skipped when the file is there, so a turn resumes where it stopped.
# `scripts/cycle.sh <name> <base repo> <teacher jsonl>`; files in ~/work/my/okay-desk-private/cycle/<name>/.
# Run it with nohup: a turn takes about two and a half hours on the M4, one MLX job at a time.
set -e
cd "$(dirname "$0")/.."
name=$1; repo=$2; teacher=$3
P=$HOME/work/my/okay-desk-private
C=$P/cycle/$name
L=$HOME/work/my/mlx-scala/native-lib/out/lib
base=$HOME/.cache/huggingface/hub/models--$(echo "$repo" | sed 's#/#--#')/snapshots/main
mkdir -p "$C"
run() { sbt -batch 'set bench / javaOptions += "-Xmx26g"' "bench/runMain okay.desk.bench.$1"; }
# 1. the heads of split A: the first 2000 Train tasks, en and ru, read as the page reads (echo 2), no rewrite
[ -f "$C/headsA.bin" ] || SMALL_DIR=$base LOGIC_DIR=$base MLX_SCALA_LIB=$L READ_ECHO=2 READ_TRAIN=2000 \
  READ_SAVE=$C/headsA.bin run StatementRead > "$C/headsA.txt" 2>&1
# 2. the adapter through them: split B (Train 2000–4000) and the teacher's rewrites
[ -f "$C/adapter.safetensors" ] || JOINT_TEACHER=$teacher MLX_SCALA_LIB=$L \
  run "JointTrain $repo $C/headsA.bin $C/adapter.safetensors 1 32 1e-4 0.1" > "$C/joint.txt" 2>&1
[ -f "$C/adapter.page.safetensors" ] || run "SemanticExport $C/adapter.safetensors $C/adapter.page.safetensors" \
  > /dev/null 2>&1
# 3. the reader by the page's recipe over the adapted core, three seeds; seed 1's reader saved
for seed in 1 2 3; do
  [ -f "$C/read-$seed.txt" ] && grep -q "^\[info\] Collection ru" "$C/read-$seed.txt" && continue
  save=""; [ "$seed" = 1 ] && save=$C/reader.bin
  env ${save:+READ_SAVE=$save} SMALL_DIR=$base LOGIC_DIR=$base SEMANTIC_NATIVE_ADAPTER=$C/adapter.page.safetensors \
    MLX_SCALA_LIB=$L READ_ECHO=2 READ_SEED=$seed READ_TRAIN=4000 READ_TEACHER=$teacher \
    READ_TEACHER_TEST=$P/teacher/test.jsonl READ_OUT=$C/out-$seed.tsv run StatementRead > "$C/read-$seed.txt" 2>&1
  # 4. its rows in the history
  scala-cli run scripts/history.scala -- "$name" "$seed" "$C/read-$seed.txt" "cycle: base $repo, teacher $teacher" \
    > /dev/null 2>&1
done
date > "$C/done"
