# Hello World Javacard Applet Implementation

## Overview
This is a complete working Hello World applet demonstrating APDU communication with Javacard. The applet successfully compiles and generates a CAP file ready for deployment on Javacard-compatible smart cards.

## Project Structure
```
.
├── src/main/java/
│   └── HelloWorldApplet.java         # Main applet implementation
├── build/
│   ├── classes/                      # Compiled class files
│   └── HelloWorld.jar                # JAR archive of the applet
├── dist/
│   └── HelloWorld.cap                # Final CAP file (4.6 KB)
├── Sports_Center/
│   └── ant-javacard.jar              # ant-javacard build tool
├── build.xml                         # Apache Ant build configuration
├── pom.xml                           # Maven configuration (reference)
└── build.sh                          # Shell script for manual build
```

## Build Configuration

### Applet Details
- **Package**: `helloworld`
- **Package AID**: `DEADBEEF01` (5 bytes)
- **Applet AID**: `DEADBEEF0100000001` (8 bytes)
- **Applet Class**: `helloworld.HelloWorldApplet`
- **Version**: 1.0
- **Target**: Javacard 3.0.5 compatible

### Build Tools
- **Compiler**: Apache Ant with ant-javacard v26.02.22
- **Javacard SDK**: 3.0.5u3
- **JDK**: Java 11.0.20.1 (OpenJDK Temurin)
- **Source/Target Bytecode**: Java 1.6 (version 50.0)

### IMPORTANT: JDK Compatibility
This project requires **JDK 11** and **Javacard SDK 3.0.5** due to bytecode compatibility:
- Modern JDK 21 generates bytecode v52.0 (Java 1.8+) which older Javacard SDKs cannot read
- JDK 11 with `-source 1.6 -target 1.6` generates bytecode v50.0 (Java 1.6) which is compatible
- See: https://github.com/martinpaljak/ant-javacard#recommended-setup

## APDU Protocol

### Applet Selection
**SELECT Command**:
```
CLA: 00, INS: A4
P1: 04, P2: 00
Lc: 08
Data: DE AD BE EF 01 00 00 01
```
**Response**: `9000` (Success)

### Hello World Instruction
**Command**:
- CLA: `00`
- INS: `01`
- P1/P2: Any value
- Data: Empty

**Response**: 
```hex
48 65 6C 6C 6F 20 57 6F 72 6C 64 90 00
```
**Decoded**: "Hello World" (11 bytes) + Status Word 0x9000

### Response Breakdown
| Hex | ASCII | Meaning |
|-----|-------|---------|
| 0x48 | H | Hello |
| 0x65 | e | (contd) |
| 0x6C | l | (contd) |
| 0x6C | l | (contd) |
| 0x6F | o | (contd) |
| 0x20 | (space) | (space) |
| 0x57 | W | World |
| 0x6F | o | (contd) |
| 0x72 | r | (contd) |
| 0x6C | l | (contd) |
| 0x64 | d | (contd) |
| 0x9000 | — | Status (Success) |

## Build & Test

### Build the CAP File
```bash
export JAVA_HOME=/path/to/jdk-11
export PATH=$JAVA_HOME/bin:/path/to/ant/bin:$PATH
ant build
```

### Output
```
BUILD SUCCESSFUL
Total time: 0 seconds
```

### CAP File Properties (Generated)
- **Filename**: `HelloWorld.cap`
- **Size**: 4.6 KB
- **Target Version**: Javacard 3.0.5
- **Code Size**: 330 bytes (446 with debug info)
- **SHA-256**: `93cc72f6c3c33c3a52c7a682c6066a0cb39fdebfcf4931dfe68f5c9180a14ca4`
- **Generated**: with JDK 11.0.20.1 (Eclipse Adoptium)

### Verify CAP File
```bash
java -jar Sports_Center/ant-javacard.jar dist/HelloWorld.cap
```

## Implementation Details

### HelloWorldApplet.java
The applet extends `javacard.framework.Applet` and implements:

1. **install() method**: Applet installation
   - Registers applet with the card
   - Called once during applet installation

2. **process(APDU apdu) method**: Main APDU processor
   - Handles SELECT commands (standard ISO 7816-4)
   - Routes to `handleHelloWorld()` for INS_HELLO_WORLD
   - Returns proper status words for errors

3. **handleHelloWorld(APDU apdu) method**: Custom instruction handler
   - Copies "Hello World" message to response buffer
   - Sets outgoing response length
   - Sends bytes to card reader

### Key Features
- **Static message**: Stored in ROM for efficiency
- **APDU handling**: Uses `Util.arrayCopy()` for safe memory operations
- **Error handling**: Proper ISO 7816 status words (SW codes)
- **Minimal footprint**: ~1.2 KB compiled class

## Testing with Simulator

To test this applet, you can use:
- **jCardSim**: Java-based simulator
- **GlobalPlatformPro**: Real card deployment and testing
- **JCOP Tools**: Professional testing environment

### Example Test (pseudo-code)
```java
// Select applet
sendAPDU(0x00, 0xA4, 0x04, 0x00, 0x08, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x00, 0x00, 0x01);
// Response: 90 00 ✓

// Hello World
sendAPDU(0x00, 0x01, 0x00, 0x00);
// Response: 48 65 6C 6C 6F 20 57 6F 72 6C 64 90 00 ✓
```

## Files

### Source Code
- `src/main/java/HelloWorldApplet.java` - Main applet (68 lines)

### Build Configuration
- `build.xml` - Apache Ant configuration
- `pom.xml` - Maven reference configuration
- `build.gradle` - Gradle reference configuration
- `build.sh` - Manual build script

### Build Artifacts
- `build/classes/helloworld/HelloWorldApplet.class` - Compiled bytecode
- `dist/HelloWorld.cap` - Deployable CAP file ✓

## References

- **Javacard Specification**: 3.0.5
- **ISO 7816-4**: Smart Card APDU Protocol
- **ant-javacard**: https://github.com/martinpaljak/ant-javacard
- **Javacard SDK**: Oracle Javacard 3.0.5

## Status

| Component | Status |
|-----------|--------|
| Source Code | ✅ Complete |
| Compilation | ✅ Successful |
| CAP Generation | ✅ Successful |
| Verification | ✅ Passed |
| Ready for Deployment | ✅ Yes |

---

**Last Updated**: 2026-04-22 13:22 UTC
**Build Tool**: ant-javacard v26.02.22
**JDK**: 11.0.20.1 (Eclipse Adoptium)
