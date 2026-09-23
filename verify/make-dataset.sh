#!/usr/bin/env bash
# 造一个"游戏数据目录"（= mods/ + settings 等），给 headless 测试和真客户端用。
#
#   verify/make-dataset.sh <目标目录> [要装的其它模组路径或目录...]
#
# 固定只装本仓库的 combineunit.jar（跑前请先 ./gradlew --offline deploy）。
# 组合单位测试**必须**在"只有 combineunit"的数据目录里跑：装了 combine 的话，
# 单位侧机制会来自 combine 的那一份，这个仓库的改动根本没被验到。
#
# 例子：
#   verify/make-dataset.sh /tmp/mp_unit/data                 # 干净的单模组数据目录
#   verify/make-dataset.sh /tmp/mp_unit_js/data /tmp/mods/xxx.jar
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${COMBINEUNIT_JAR:-$ROOT/build/libs/combineunit.jar}"

if [ $# -lt 1 ]; then
  echo "用法: $0 <目标目录> [模组路径...]" >&2
  exit 2
fi
target="$1"; shift

[ -f "$JAR" ] || { echo "先 ./gradlew --offline deploy（没找到 $JAR）" >&2; exit 2; }

mkdir -p "$target/mods"
# 干净起步：只放 combineunit.jar + 显式要装的模组
rm -f "$target/mods/combineunit.jar" "$target/mods/drv.jar"
cp "$JAR" "$target/mods/combineunit.jar"
for m in "$@"; do
  if [ -d "$m" ]; then
    cp -r "$m" "$target/mods/"
  elif [ -f "$m" ]; then
    cp "$m" "$target/mods/"
  else
    echo "[warn] 跳过不存在的模组: $m" >&2
  fi
done
echo "[verify] 数据目录好了：$target"
ls -l "$target/mods"
