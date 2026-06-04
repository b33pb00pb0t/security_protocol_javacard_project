#!/bin/bash
set -e

# 1. Pulisci il progetto precedente
echo "--- Cleaning ---"
ant clean

# 2. Compila la Java Card (Genera il file .cap)
echo "--- Building Applet (.cap) ---"
ant build-cap

# 3. Compila il Terminale/Host (Genera i .class)
echo "--- Building Host (Frontend) ---"
ant build-host

# 4. Esecuzione
echo "--- Launching Application ---"
# Determina il separatore di classpath in base al sistema (Linux/Mac vs Windows)
SEP=":"
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then SEP=";"; fi

# Definisci il classpath includendo la cartella delle classi compilate e i jar in lib
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
