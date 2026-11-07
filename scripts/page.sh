#!/bin/sh
# THE PAGE'S SELF-TEST: the page linked, served with the cut model's files and the readers' skill from the caches,
# run in Chromium by scripts/page.scala (`GL=1` for WebGL2, `PHONE=1` as a phone: WebGL2, the CPU head)
set -e
cd "$(dirname "$0")/.."
MODEL_DIR=${MODEL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19/snapshots/main}
SKILL=${SKILL:-$HOME/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill}
# the student (§21), the default model: 12 layers distilled toward the cut's layer-19 state, the same skill file
SMALL_DIR=${SMALL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main}
CORE_DIR=${CORE_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-no-free-ru-m4/snapshots/main}
sbt -batch web/fullLinkJS > /dev/null
web=web/target/web; mkdir -p $web/model
cp web/page/index.html $web/ && cp web/target/scala-3.3.8/okay-desk-web-opt/main.js $web/
cp -r web/page/sample $web/
cp -r web/page/words $web/
cp -r web/page/lang $web/
for f in config.json tokenizer.json; do cp "$MODEL_DIR/$f" $web/model/; done
sbt -batch "bench/runMain okay.desk.bench.SplitWeights $MODEL_DIR $PWD/$web/model" > /dev/null
cp "$SKILL" $web/model/readers.skill
mkdir -p $web/model-small && cp "$SMALL_DIR/config.json" "$SMALL_DIR/tokenizer.json" $web/model-small/
sbt -batch "bench/runMain okay.desk.bench.SplitWeights $SMALL_DIR $PWD/$web/model-small" > /dev/null
cp "$SKILL" $web/model-small/readers.skill
if [ -d "$CORE_DIR" ]; then
  mkdir -p $web/model-core && cp "$CORE_DIR/config.json" "$CORE_DIR/tokenizer.json" $web/model-core/
  sbt -batch "bench/runMain okay.desk.bench.SplitWeights $CORE_DIR $PWD/$web/model-core" > /dev/null
  cp "$SKILL" $web/model-core/readers.skill
fi
# another server on the port would answer instead of this build (a stale one did, §29): refuse to measure it
if lsof -iTCP:8734 -sTCP:LISTEN > /dev/null 2>&1; then echo "port 8734 is taken: $(lsof -iTCP:8734 -sTCP:LISTEN | tail -1)"; exit 1; fi
jwebserver -d "$PWD/$web" -p 8734 -o none > /dev/null 2>&1 &
server=$!
trap 'kill $server' EXIT
sleep 1
scala-cli run scripts/page.scala -- "http://localhost:8734/?selftest"
