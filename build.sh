#!/bin/bash
set -e

JCKIT="/home/b33pb00pb0t/.local/share/Trash/files/javacard-gradle-template/libs-sdks/jc310b43_kit"
ANT_JAVACARD="Sports_Center/ant-javacard.jar"
SRC_DIR="src/main/java"
BUILD_DIR="build"
DIST_DIR="dist"
CLASSES_DIR="$BUILD_DIR/classes"

# Setup paths
export ANT_HOME="/tmp/apache-ant-1.10.14"
export PATH="$ANT_HOME/bin:$PATH"

echo "=== Hello World Javacard Applet Build ==="
echo "Java version: $(java -version 2>&1 | head -1)"
echo "Ant version: $(ant -version 2>&1 | head -1)"
echo ""

# Create directories
mkdir -p "$CLASSES_DIR" "$DIST_DIR"

# Compile
echo "1. Compiling HelloWorldApplet.java..."
javac \
  -cp "$JCKIT/lib/api_classic-3.1.0.jar" \
  -d "$CLASSES_DIR" \
  -source 1.8 -target 1.8 \
  "$SRC_DIR"/HelloWorldApplet.java
echo "   ✓ Compilation successful"

# Create CAP file with ant-javacard
echo "2. Building CAP file..."
java -jar "$ANT_JAVACARD" \
  --jckit "$JCKIT" \
  --output "$DIST_DIR/HelloWorld.cap" \
  --applet 0xDE:0xAD:0xBE:0xEF:0x01:0x00:0x00:0x01 \
  --package 0xDE:0xAD:0xBE:0xEF \
  helloworld.HelloWorldApplet \
  "$CLASSES_DIR"

# Verify build output
if [ -f "$DIST_DIR/HelloWorld.cap" ]; then
  echo "   ✓ CAP file created successfully"
  echo ""
  echo "=== Build Summary ==="
  echo "Output: $DIST_DIR/HelloWorld.cap"
  ls -lh "$DIST_DIR"/HelloWorld.cap
else
  echo "   ✗ Failed to create CAP file"
  exit 1
fi
