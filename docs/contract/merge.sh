#!/usr/bin/env bash
# 合併 fragments/*.yaml 成 openapi.yaml。
# 片段以 "# @info"、"# @paths"、"# @security"、"# @responses"、"# @schemas" 分段；
# 各段內容須已含正確縮排（paths 2 格；components 下的項目 4 格）。
set -euo pipefail
cd "$(dirname "$0")"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for f in fragments/*.yaml; do
  awk -v dir="$tmp" '
    /^# @(info|paths|security|responses|schemas)[[:space:]]*$/ { sec=$2; sub(/^@/, "", sec); next }
    sec != "" { print >> (dir "/" sec ".part") }
  ' "$f"
done
for s in info paths security responses schemas; do touch "$tmp/$s.part"; done
{
  cat "$tmp/info.part"
  echo "paths:"
  cat "$tmp/paths.part"
  echo "components:"
  echo "  securitySchemes:"
  cat "$tmp/security.part"
  echo "  responses:"
  cat "$tmp/responses.part"
  echo "  schemas:"
  cat "$tmp/schemas.part"
} > openapi.yaml
echo "merged: $(wc -l < openapi.yaml) lines"
