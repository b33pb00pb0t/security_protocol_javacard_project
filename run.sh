#!/usr/bin/env bash
# Build and execution script for the Sports Center project.
# Usage: ./run.sh [simulator|frontend]

set -euo pipefail

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
    *) CP_SEP=":" ;;
esac

BUILD_DIR="build"
CLASSPATH="${BUILD_DIR}${CP_SEP}lib/*"

echo "--- Starting Build Process ---"
mkdir -p "$BUILD_DIR"

javac -source 8 -target 8 -cp "$CLASSPATH" -d "$BUILD_DIR" \
    src/applet/*.java \
    src/backend/*.java \
    src/frontend/*.java \
    src/terminals/*.java \
    simulator/*.java

echo "--- Build Completed Successfully ---"

case "${1:-}" in
    simulator)
        echo "Launching Membership Simulator..."
        java -cp "$CLASSPATH" RunMembershipSimulator
        ;;
    frontend)
        echo "Launching Frontend..."
        java -cp "$CLASSPATH" frontend.Main
        ;;
    *)
        echo "Usage: ./run.sh [simulator|frontend]"
        echo "  simulator -> Run the Membership Simulator"
        echo "  frontend  -> Run the Swing Frontend application"
        ;;
esac
