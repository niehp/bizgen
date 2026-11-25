#!/usr/bin/env bash
# JDT / SQLite 依存を持つため、ビルドは Maven に統一する。
# 初回取得後は `mvn -o` でオフラインビルドも可能。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

if command -v mvn >/dev/null 2>&1; then
  (cd "$ROOT" && mvn -q -DskipTests package)
else
  echo "[BUILD FAILED] Maven が見つかりません。JDT / SQLite の依存解決に Maven が必要です。" >&2
  exit 1
fi

JAR="$ROOT/target/bizgen-agent.jar"
if [ ! -f "$JAR" ]; then
  echo "[BUILD FAILED] $JAR が生成されませんでした。" >&2
  exit 1
fi
echo "[BUILD OK] $JAR"
echo "[RUN] java -jar \"$JAR\""
