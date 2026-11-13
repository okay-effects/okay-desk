#!/bin/sh
# THE PAGE PUBLISHED to GitHub Pages (https://okay-effects.github.io/okay-desk/): linked, its files onto the gh-pages
# branch: the app, CPU worker, data, the reader student, and an explicitly experimental native understanding profile.
set -e
cd "$(dirname "$0")/.."
SKILL=${SKILL:-$HOME/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill}
# the student (§21), the default model: 12 layers distilled toward the cut's layer-19 state, the same skill file
SMALL_DIR=${SMALL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main}
FACTS=${FACTS:-$HOME/.cache/okay-desk/facts-readers-small.json}
RELATIONS=${RELATIONS:-$HOME/.cache/okay-desk/relations-readers-small.json}
STATEMENT=${STATEMENT:-$HOME/.cache/okay-desk/statement-reader.bin}
SEMANTIC_ADAPTER=${SEMANTIC_ADAPTER:-$HOME/.cache/okay-desk/semantic-reference-v5/native-adapter.safetensors}
SEMANTIC_DIR=${SEMANTIC_DIR:-$HOME/.cache/okay-desk/semantic-full28}
test -s "$FACTS"
test -s "$RELATIONS"
test -s "$SEMANTIC_ADAPTER"
if [ -f "$SEMANTIC_ADAPTER" ]; then
  test -s "$SEMANTIC_DIR/model.safetensors"
  test -s "$SEMANTIC_DIR/config.json"
  test -s "$SEMANTIC_DIR/tokenizer.json"
fi
sbt -batch coreJVM/test uiJVM/test web/fullLinkJS worker/fullLinkJS > /dev/null
out=/tmp/okay-desk-pages
# THE BRANCH HAS NO HISTORY: every publish is one orphan commit, force-pushed — each kept a 250 MB copy of the weights
# and the branch grew past what GitHub Pages builds ("Page build failed" with no reason, 2026-11-06)
git worktree prune
rm -rf "$out"
git branch -D gh-pages-next >/dev/null 2>&1 || true
git worktree add -q --orphan -b gh-pages-next "$out"
cp web/page/index.html "$out/" && cp web/target/scala-3.3.8/okay-desk-web-opt/main.js "$out/"
cp worker/target/scala-3.3.8/okay-desk-worker-opt/main.js "$out/worker.js"
# the script's address carries the build's hash: a browser that cached the page gets the new script without a hard reload
if [ -f "$SEMANTIC_ADAPTER" ]; then
  v=$(cat "$out/main.js" "$out/worker.js" "$FACTS" "$RELATIONS" "$SEMANTIC_ADAPTER" \
    "$SEMANTIC_DIR/model.safetensors" "$SEMANTIC_DIR/config.json" "$SEMANTIC_DIR/tokenizer.json" \
    | md5 -q | cut -c1-12)
else
  v=$(cat "$out/main.js" "$out/worker.js" "$FACTS" "$RELATIONS" | md5 -q | cut -c1-12)
fi
# the statement reader is fetched by the build's address too: a retrained one must change it
if [ -f "$STATEMENT" ]; then v=$(echo "$v$(md5 -q "$STATEMENT")" | md5 -q | cut -c1-12); fi
# the logic ability's adapter (specs/core-joint.md §6), on while a task is read
STATEMENT_ADAPTER=${STATEMENT_ADAPTER:-$HOME/.cache/okay-desk/statement-adapter.safetensors}
if [ -f "$STATEMENT_ADAPTER" ]; then v=$(echo "$v$(md5 -q "$STATEMENT_ADAPTER")" | md5 -q | cut -c1-12); fi
sed -i "" "s#src=\"main.js\"#src=\"main.js?v=$v\"#" "$out/index.html"
rm -rf "$out/sample" && cp -r web/page/sample "$out/"
# the data's own words (§29), a file a table: the examples'
rm -rf "$out/words" && cp -r web/page/words "$out/"
# the languages loaded on demand (calculus.md §18): lang/<code>.json
rm -rf "$out/lang" && cp -r web/page/lang "$out/"
# the student, the page's default (§21): its own files, the same skill
mkdir -p "$out/model-small"
cp web/page/qwen-LICENSE.txt "$out/model-small/LICENSE"
cp "$SMALL_DIR/config.json" "$SMALL_DIR/tokenizer.json" "$out/model-small/" && cp "$SKILL" "$out/model-small/readers.skill"
sbt -batch "bench/runMain okay.desk.bench.SplitWeights $SMALL_DIR $out/model-small" > /dev/null
# Trained on the 12-layer student's states; absence leaves semantic reading unavailable.
FACTS=${FACTS:-$HOME/.cache/okay-desk/facts-readers-small.json}
if [ -f "$FACTS" ]; then cp "$FACTS" "$out/model-small/facts.skill"; fi
RELATIONS=${RELATIONS:-$HOME/.cache/okay-desk/relations-readers-small.json}
if [ -f "$RELATIONS" ]; then cp "$RELATIONS" "$out/model-small/relations-v1.skill"; fi
# The statement reader of logic tasks (specs/formalization.md §12.1): heads over the student, its words, its bars.
STATEMENT=${STATEMENT:-$HOME/.cache/okay-desk/statement-reader.bin}
if [ -f "$STATEMENT" ]; then cp "$STATEMENT" "$out/model-small/statement.reader"; fi
if [ -f "$STATEMENT_ADAPTER" ]; then cp "$STATEMENT_ADAPTER" "$out/model-small/statement-adapter.safetensors"; fi
# Explicit experimental ?understanding path; the normal chat keeps the measured legacy readers.
if [ -f "$SEMANTIC_ADAPTER" ]; then
  mkdir -p "$out/model-understanding"
  cp "$SEMANTIC_DIR/config.json" "$SEMANTIC_DIR/tokenizer.json" "$out/model-understanding/"
  cp "$SEMANTIC_ADAPTER" "$out/model-understanding/semantic-adapter.safetensors"
  cp web/page/qwen-LICENSE.txt "$out/model-understanding/LICENSE"
  cp web/page/understanding-model.txt "$out/model-understanding/README.txt"
  sbt -batch "bench/runMain okay.desk.bench.SplitWeights $SEMANTIC_DIR $out/model-understanding" > /dev/null
fi

printf '{"build":"%s","base":"%s"}\n' "$v" "$(git rev-parse --short HEAD)" > "$out/build.json"
touch "$out/.nojekyll"
git -C "$out" add -A
git -C "$out" commit -q -m "the page, build $v"
git -C "$out" push -q -f origin gh-pages-next:gh-pages
git worktree remove --force "$out"
git branch -D gh-pages-next >/dev/null
echo "published: https://okay-effects.github.io/okay-desk/"
