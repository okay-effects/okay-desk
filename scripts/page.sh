#!/bin/sh
# THE PAGE'S SELF-TEST: the page linked, served with the cut model's files and the readers' skill from the caches,
# run in Chromium by scripts/page.scala (`GL=1` for WebGL2)
set -e
cd "$(dirname "$0")/.."
MODEL_DIR=${MODEL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19/snapshots/main}
SKILL=${SKILL:-$HOME/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill}
sbt -batch web/fullLinkJS > /dev/null
web=web/target/web; mkdir -p $web/model
cp web/page/index.html $web/ && cp web/target/scala-3.3.8/okay-desk-web-opt/main.js $web/
for f in config.json tokenizer.json model.safetensors; do cp "$MODEL_DIR/$f" $web/model/; done
cp "$SKILL" $web/model/readers.skill
jwebserver -d "$PWD/$web" -p 8734 -o none > /dev/null 2>&1 &
server=$!
trap 'kill $server' EXIT
sleep 1
scala-cli run scripts/page.scala -- "http://localhost:8734/?selftest"
