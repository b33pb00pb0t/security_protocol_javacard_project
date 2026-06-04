#!/bin/bash
set -e

echo "--- Cleaning ---"
ant clean

echo "--- Building Applet (.cap) ---"
ant build-cap

echo "--- Building Host (Frontend) ---"
ant build-host

echo "--- Launching Application ---"
# Select the platform classpath separator.
SEP=":"
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then SEP=";"; fi

CP="build/classes-host"
for jar in lib/*.jar; do
    CP="$CP$SEP$jar"
done

case "${1:-}" in
    --simulator)
        java -cp "$CP" RunMembershipSimulator
        ;;
    --hardware-smoke)
        shift
        java -cp "$CP" tools.HardwareSmokeTest "$@"
        ;;
    --hardware)
        java -cp "$CP" frontend.Main --hardware
        ;;
    *)
        java -cp "$CP" frontend.Main "$@"
        ;;
esac
