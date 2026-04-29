#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
JCARDSIM_VERSION="2.2.2"
JAR_DIR="$ROOT_DIR/lib"
JAR_PATH="$JAR_DIR/jcardsim-$JCARDSIM_VERSION.jar"
OUT_DIR="$ROOT_DIR/.sim-build"
APPLET_SRC="$ROOT_DIR/src/com/sports/recreation/MembershipApplet.java"
HARNESS_SRC="$ROOT_DIR/simulator/RunMembershipSimulator.java"

mkdir -p "$JAR_DIR" "$OUT_DIR"

if [[ ! -f "$JAR_PATH" ]]; then
  curl -L --fail -o "$JAR_PATH" "https://repo1.maven.org/maven2/com/licel/jcardsim/$JCARDSIM_VERSION/jcardsim-$JCARDSIM_VERSION.jar"
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

javac -cp "$JAR_PATH" -d "$OUT_DIR" "$APPLET_SRC" "$HARNESS_SRC"
java -cp "$JAR_PATH:$OUT_DIR" RunMembershipSimulator
