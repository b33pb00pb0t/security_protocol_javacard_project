#!/bin/bash
# Build and execution script for the Sports Center project
# Usage: ./run.sh [simulator|frontend]

# Exit immediately if a command exits with a non-zero status
set -e

echo "--- Starting Build Process ---"

# Create the build directory if it doesn't exist
mkdir -p build

# Compile all Java sources with Java 8 compatibility to avoid version mismatch
javac -source 8 -target 8 -cp "build;lib/*" -d build \
    src/applet/*.java \
    src/backend/*.java \
    src/frontend/*.java \
    src/terminals/*.java \
    simulator/*.java

echo "--- Build Completed Successfully ---"

# Handle execution based on the provided argument
case "${1:-}" in
    simulator)
        echo "Launching Membership Simulator..."
        java -cp "build;lib/*" RunMembershipSimulator
        ;;
    frontend)
        echo "Launching Frontend..."
        # Note: Replace 'frontend.Main' with the actual class name containing your main method
        java -cp "build;lib/*" frontend.Main
        ;;
    *)
        echo "Usage: ./run.sh [simulator|frontend]"
        echo "  simulator   -> Run the Membership Simulator"
        echo "  frontend -> Run the Frontend application"
        ;;
esac