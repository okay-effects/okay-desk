#!/bin/sh
# THE PAGE PUBLISHED to GitHub Pages (https://okay-effects.github.io/okay-desk/): linked, its files onto the gh-pages
# branch — index.html, main.js, and model/: config.json, tokenizer.json, readers.skill, the cut model's weights split
# into parts under 100 MB (manifest.json lists them; the page joins them) — pushed. The model from the HuggingFace
# cache ($MODEL_DIR), the skill from ~/.cache/okay-desk ($SKILL).
set -e
cd "$(dirname "$0")/.."
MODEL_DIR=${MODEL_DIR:-$HOME/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19/snapshots/main}
SKILL=${SKILL:-$HOME/.cache/okay-desk/readers-Qwen3-0.6B-4bit-cut19-19.skill}
sbt -batch coreJVM/test web/fullLinkJS > /dev/null
out=/tmp/okay-desk-pages
# THE BRANCH HAS NO HISTORY: every publish is one orphan commit, force-pushed — each kept a 250 MB copy of the weights
# and the branch grew past what GitHub Pages builds ("Page build failed" with no reason, 2026-11-06)
git worktree prune
rm -rf "$out"
git branch -D gh-pages-next >/dev/null 2>&1 || true
git worktree add -q --orphan -b gh-pages-next "$out"
cp web/page/index.html "$out/" && cp web/target/scala-3.3.8/okay-desk-web-opt/main.js "$out/"
# the script's address carries the build's hash: a browser that cached the page gets the new script without a hard reload
v=$(md5 -q "$out/main.js" | cut -c1-8)
sed -i "" "s#src=\"main.js\"#src=\"main.js?v=$v\"#" "$out/index.html"
rm -rf "$out/sample" && cp -r web/page/sample "$out/"
mkdir -p "$out/model"
cp "$MODEL_DIR/config.json" "$MODEL_DIR/tokenizer.json" "$out/model/" && cp "$SKILL" "$out/model/readers.skill"
# the weights as safetensors files of a few layers each (manifest.json lists them): read one by one as one
sbt -batch "bench/runMain okay.desk.bench.SplitWeights $MODEL_DIR $out/model" > /dev/null
touch "$out/.nojekyll"
git -C "$out" add -A
git -C "$out" commit -q -m "the page, from $(git rev-parse --short HEAD)"
git -C "$out" push -q -f origin gh-pages-next:gh-pages
git worktree remove --force "$out"
git branch -D gh-pages-next >/dev/null
echo "published: https://okay-effects.github.io/okay-desk/"
