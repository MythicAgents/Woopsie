#!/bin/bash
# Test script to build Windows EXE with bundled JRE on Debian host

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_CODE_PATH="$SCRIPT_DIR/Payload_Type/woopsie/woopsie/agent_code"
BUILD_DIR="$AGENT_CODE_PATH/target/windows_build"
DIST_DIR="$AGENT_CODE_PATH/target/woopsie-windows"

echo "=== Woopsie Windows EXE Build Test ==="
echo

# Check prerequisites
echo "[1/6] Checking prerequisites..."
if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven not found. Install with: sudo apt install maven"
    exit 1
fi

if ! command -v java &> /dev/null; then
    echo "ERROR: Java not found. Install with: sudo apt install openjdk-17-jdk"
    exit 1
fi

echo "✓ Maven: $(mvn -v | head -1)"
echo "✓ Java: $(java -version 2>&1 | head -1)"
echo

# Install Launch4j if not present
if [ ! -d "/opt/launch4j" ]; then
    echo "[2/6] Installing Launch4j..."
    sudo mkdir -p /opt/launch4j
    cd /tmp
    wget -q https://sourceforge.net/projects/launch4j/files/launch4j-3/3.50/launch4j-3.50-linux-x64.tgz/download -O launch4j.tgz
    sudo tar -xzf launch4j.tgz -C /opt/
    sudo chmod +x /opt/launch4j/launch4j
    rm launch4j.tgz
    echo "✓ Launch4j installed to /opt/launch4j"
else
    echo "[2/6] Launch4j already installed"
    echo "✓ /opt/launch4j/launch4j"
fi
echo

# Download Windows JRE if not present
if [ ! -d "/opt/jre-windows" ]; then
    echo "[3/6] Downloading Adoptium JRE 17 for Windows..."
    sudo mkdir -p /opt/jre-windows
    cd /tmp
    wget -q https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jre_x64_windows_hotspot_17.0.13_11.zip -O jre-windows.zip
    unzip -q jre-windows.zip
    sudo mv jdk-17.0.13+11-jre /opt/jre-windows/jre
    rm jre-windows.zip
    echo "✓ Windows JRE installed to /opt/jre-windows/jre"
else
    echo "[3/6] Windows JRE already downloaded"
    echo "✓ /opt/jre-windows/jre"
fi
echo

# Build JAR
echo "[4/6] Building JAR with Maven..."
cd "$AGENT_CODE_PATH"
mvn clean package -q
if [ ! -f "target/woopsie.jar" ]; then
    echo "ERROR: JAR build failed"
    exit 1
fi
echo "✓ JAR built: target/woopsie.jar ($(du -h target/woopsie.jar | cut -f1))"
echo

# Create Windows build
echo "[5/6] Creating Windows EXE with bundled JRE..."
mkdir -p "$BUILD_DIR"
rm -rf "$BUILD_DIR"/*

# Copy JAR
cp target/woopsie.jar "$BUILD_DIR/"

# Copy bundled JRE
echo "  Copying JRE (~50-80MB, this may take a moment)..."
cp -r /opt/jre-windows/jre "$BUILD_DIR/"

# Generate Launch4j config
cat > "$BUILD_DIR/launch4j_config.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>console</headerType>
  <jar>$BUILD_DIR/woopsie.jar</jar>
  <outfile>$AGENT_CODE_PATH/target/woopsie.exe</outfile>
  <errTitle>Woopsie Agent Error</errTitle>
  <cmdLine></cmdLine>
  <chdir>.</chdir>
  <priority>normal</priority>
  <downloadUrl></downloadUrl>
  <supportUrl></supportUrl>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
  <manifest></manifest>
  <icon></icon>
  <jre>
    <path>./jre</path>
    <bundledJre64Bit>true</bundledJre64Bit>
    <bundledJreAsFallback>false</bundledJreAsFallback>
    <minVersion>17</minVersion>
    <jdkPreference>preferJre</jdkPreference>
    <runtimeBits>64</runtimeBits>
  </jre>
</launch4jConfig>
EOF

# Run Launch4j
/opt/launch4j/launch4j "$BUILD_DIR/launch4j_config.xml"

if [ ! -f "$AGENT_CODE_PATH/target/woopsie.exe" ]; then
    echo "ERROR: Launch4j failed to create EXE"
    exit 1
fi
echo "✓ EXE created: target/woopsie.exe ($(du -h target/woopsie.exe | cut -f1))"
echo

# Package distribution
echo "[6/6] Creating distribution package..."
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
cp "$AGENT_CODE_PATH/target/woopsie.exe" "$DIST_DIR/"
cp -r "$BUILD_DIR/jre" "$DIST_DIR/"

echo "✓ Distribution package created: target/woopsie-windows/"
echo

# Summary
echo "=== Build Complete ==="
echo
echo "Output directory: $DIST_DIR"
echo "Contents:"
ls -lh "$DIST_DIR" | tail -n +2
echo
echo "Total size: $(du -sh "$DIST_DIR" | cut -f1)"
echo
echo "Deployment instructions:"
echo "  1. Copy entire 'woopsie-windows/' directory to Windows target"
echo "  2. Both woopsie.exe and jre/ folder must stay together"
echo "  3. Run: woopsie.exe"
echo
echo "Note: No Java installation required on target system!"
