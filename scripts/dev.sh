#!/bin/sh
# Build and serve the real chat and its CPU worker. Exact tools also work without model weights.
set -eu
cd "$(dirname "$0")/.."
PORT=${PORT:-8734}
BIND=${BIND:-127.0.0.1}
if lsof -iTCP:"$PORT" -sTCP:LISTEN > /dev/null 2>&1; then
  echo "Port $PORT is already in use. Choose another PORT." >&2
  exit 1
fi
sbt -batch web/fullLinkJS worker/fullLinkJS
web="$PWD/web/target/web"
mkdir -p "$web"
cp web/page/index.html web/target/scala-3.3.8/okay-desk-web-opt/main.js "$web/"
cp worker/target/scala-3.3.8/okay-desk-worker-opt/main.js "$web/worker.js"
cp -R web/page/sample web/page/words web/page/lang "$web/"
SMALL_DIR=${SMALL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main}
SKILL=${SKILL:-$HOME/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill}
if [ ! -f "$web/model-small/manifest.json" ] && [ -f "$SMALL_DIR/model.safetensors" ] && [ -f "$SKILL" ]; then
  mkdir -p "$web/model-small"
  cp "$SMALL_DIR/config.json" "$SMALL_DIR/tokenizer.json" "$web/model-small/"
  cp "$SKILL" "$web/model-small/readers.skill"
  sbt -batch "bench/runMain okay.desk.bench.SplitWeights $SMALL_DIR $web/model-small"
fi
# Trained on the 12-layer student's states; absence leaves semantic reading unavailable.
FACTS=${FACTS:-$HOME/.cache/okay-desk/facts-readers-small.json}
if [ -f "$FACTS" ]; then cp "$FACTS" "$web/model-small/facts.skill"; fi
RELATIONS=${RELATIONS:-$HOME/.cache/okay-desk/relations-readers-small.json}
if [ -f "$RELATIONS" ]; then cp "$RELATIONS" "$web/model-small/relations-v1.skill"; fi
STATEMENT=${STATEMENT:-$HOME/.cache/okay-desk/statement-reader.bin}
if [ -f "$STATEMENT" ]; then cp "$STATEMENT" "$web/model-small/statement.reader"; fi
STATEMENT_ADAPTER=${STATEMENT_ADAPTER:-$HOME/.cache/okay-desk/statement-adapter.safetensors}
if [ -f "$STATEMENT_ADAPTER" ]; then cp "$STATEMENT_ADAPTER" "$web/model-small/statement-adapter.safetensors"; fi
SEMANTIC_ADAPTER=${SEMANTIC_ADAPTER:-$HOME/.cache/okay-desk/semantic-reference-v5/native-adapter.safetensors}
SEMANTIC_DIR=${SEMANTIC_DIR:-$HOME/.cache/okay-desk/semantic-full28}
if [ -f "$SEMANTIC_ADAPTER" ] && [ -f "$SEMANTIC_DIR/model.safetensors" ]; then
  mkdir -p "$web/model-understanding"
  cp "$SEMANTIC_DIR/config.json" "$SEMANTIC_DIR/tokenizer.json" "$web/model-understanding/"
  cp "$SEMANTIC_ADAPTER" "$web/model-understanding/semantic-adapter.safetensors"
  semantic_source=$(shasum -a 256 "$SEMANTIC_DIR/model.safetensors" | cut -d ' ' -f 1)
  if [ ! -f "$web/model-understanding/manifest.json" ] || \
      [ "$(cat "$web/model-understanding/source.sha256" 2>/dev/null)" != "$semantic_source" ]; then
    sbt -batch "bench/runMain okay.desk.bench.SplitWeights $SEMANTIC_DIR $web/model-understanding"
    printf '%s\n' "$semantic_source" > "$web/model-understanding/source.sha256"
  fi
  local_version=$(cat "$web/main.js" "$web/worker.js" "$SEMANTIC_ADAPTER" \
    "$SEMANTIC_DIR/model.safetensors" | md5 -q | cut -c1-12)
  sed -i "" "s#src=\"main.js\"#src=\"main.js?v=$local_version\"#" "$web/index.html"
fi
echo "Chat: http://localhost:$PORT/"
echo "CPU diagnostics: http://localhost:$PORT/?execution-test"
exec jwebserver -b "$BIND" -d "$web" -p "$PORT" -o none
