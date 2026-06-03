#!/bin/bash
set -euo pipefail

# Assumes classes are compiled into the 'build' directory
echo "Starting JavaCard Simulator..."
# Classpath includes the build folder (for classes) and lib folder (for dependencies)
java -cp "build;lib/*" RunMembershipSimulator