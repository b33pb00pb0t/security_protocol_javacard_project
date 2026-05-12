#!/bin/bash
set -euo pipefail

if [[ -n "${1:-}" ]]; then
    export JC_HOME="$1"
fi

if [[ -z "${JC_HOME:-}" ]]; then
    echo "Usage: ./build.sh /path/to/javacard-sdk"
    exit 1
fi

if [[ ! -f "$JC_HOME/lib/api_classic.jar" ]]; then
    echo "JavaCard SDK not found at: $JC_HOME"
    exit 1
fi

ant -f build.xml -Djc.home="$JC_HOME" build
