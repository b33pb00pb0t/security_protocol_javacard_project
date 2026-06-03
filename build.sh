#!/bin/bash
set -euo pipefail

# 1. Compile JavaCard Applet
if [[ -n "${JC_HOME:-}" ]]; then
    echo "Building JavaCard Applet..."
    ant -f build.xml -Djc.home="$JC_HOME" build
else
    echo "Warning: JC_HOME not set, skipping JavaCard Applet build"
fi

# 2. Compile Java System
echo "Compiling system Java sources..."
# Creates 'build' folder for compiled .class files
mkdir -p build
# Uses semicolon ';' for Windows classpath, colon ':' for Linux
# Since you are in Git Bash, sometimes it accepts ':', but ';' is safer for Windows Java
javac -cp ".;lib/*" -d build \
    src/applet/*.java \
    src/backend/*.java \
    src/frontend/*.java \
    src/terminals/*.java \
    simulator/*.java

echo "Build completed successfully."