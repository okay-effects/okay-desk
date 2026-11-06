#!/bin/sh
# THE MAP BY COMPONENT (okay-inlay specs/core.md §4.1): for every layer of the cut model, its attention silenced and
# its MLP silenced, the readers trained again on each, the bench run; one line a variant in bench/ablation.txt.
# One MLX job at a time: hours. `scripts/ablate.sh [layers...]`
set -e
cd "$(dirname "$0")/.."
REPO=${REPO:-local/Qwen3-0.6B-4bit-cut19}
out=bench/ablation.txt
layers=${*:-$(seq 0 18)}
echo "# $(date '+%F %R') $REPO: a component silenced, the readers retrained, the bench" >> $out
for l in $layers; do
  for kind in attn mlp; do
    sbt -batch "bench/runMain okay.desk.bench.Ablate $REPO $kind $l" > /dev/null
    name="$REPO-no$kind$l"
    line=$(DESK_MODELS=$name DESK_LAYER=19 DESK_READERS=1 DESK_SKIP_GENERATION=1 DESK_SKIP_MEASURE=1 DESK_RETRAIN=1 \
      sbt -batch "bench/runMain okay.desk.bench.DeskBench" 2>&1 | grep "readers |" | tail -1 | sed 's/^\[info\] //')
    echo "$line" | tee -a $out
    rm -rf "$HOME/.cache/huggingface/hub/models--local--$(echo $name | sed 's#^local/##')" "$HOME/.cache/okay-desk/readers-$(echo $name | sed 's#^local/##')-19.skill"
  done
done
