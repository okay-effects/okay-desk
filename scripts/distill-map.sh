#!/bin/sh
# A SET OF THE MAP REMOVED, THE CONTRACT DISTILLED BACK (okay-inlay specs/core.md §11): the set silenced, LoRA trained
# toward the teacher's state at layer 19 (Distill, the student branch's tool), the sample measured with the TEACHER'S
# SKILL FILE UNCHANGED through the adapter. `scripts/distill-map.sh free [steps] [rank]`; a line to bench/ablation.txt
set -e
cd "$(dirname "$0")/.."
set=$1; steps=${2:-2000}; rank=${3:-32}
teacher=local/Qwen3-0.6B-4bit-cut19
name=$(echo "$set" | sed 's/+/-/g')
variant=$teacher-no-$name
sbt -batch "bench/runMain okay.desk.bench.Silence $set" > /dev/null
sbt -batch "bench/runMain okay.desk.bench.Distill $teacher 19 $variant $steps $rank" 2>&1 | grep -E "^\[info\] (step|saved|teacher)" | sed 's/^\[info\] //' | tail -4
# the teacher's skill under the variant's name: DeskBench loads it unchanged
cp ~/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill ~/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-no-$name-19.skill
line=$(DESK_MODELS=$variant DESK_LAYER=19 DESK_READERS=1 DESK_SKIP_GENERATION=1 DESK_SKIP_MEASURE=1 DESK_ADAPTER=$HOME/.cache/okay-desk/student \
  sbt -batch "bench/runMain okay.desk.bench.DeskBench" 2>&1 | grep "readers |" | tail -1 | sed 's/^\[info\] //')
echo "$set distilled ($steps steps, rank $rank), the teacher's skill unchanged: $line" | tee -a bench/ablation.txt
