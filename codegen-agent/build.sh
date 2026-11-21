#!/usr/bin/env bash
# Maven が無い環境でもビルドできる、JDK のみのビルドスクリプト（Git Bash 用）。
set -euo pipefail
ROOT="$(cygpath -m "$(cd "$(dirname "$0")" && pwd)")"
SRC="$ROOT/src/main/java"
OUT="$ROOT/target/classes"
JAR="$ROOT/target/bizgen-agent.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$ROOT/target"
find "$SRC" -name '*.java' -print > "$ROOT/target/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -d "$OUT" @"$ROOT/target/sources.txt"
jar cfe "$JAR" com.walk_nie.bizgen.agent.AgentApplication -C "$OUT" .
echo "[BUILD OK] $JAR"
echo "[RUN] java -jar \"$JAR\""
