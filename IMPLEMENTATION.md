# Hello World Javacard Applet Implementation

## Overview
This is a simple Hello World applet demonstrating basic APDU communication with Javacard. The applet responds to a custom APDU instruction with the "Hello World" message.

## Project Structure
```
.
├── src/main/java/
│   └── HelloWorldApplet.java    # Main applet implementation
├── build/
│   ├── classes/                  # Compiled class files
│   └── HelloWorld.jar            # JAR archive of the applet
├── dist/                         # Distribution directory (for CAP file)
├── Sports_Center/
│   └── ant-javacard.jar          # Build tool for generating CAP files
├── build.xml                     # Apache Ant build configuration
├── build.gradle                  # Gradle build configuration (alternative)
├── pom.xml                       # Maven configuration (reference)
└── build.sh                      # Shell script for manual build
```

## Architecture

### HelloWorldApplet.java
The applet implements the following:

- **Package**: `helloworld` (AID: `0xDE:0xAD:0xBE:0xEF`)
- **Applet AID**: `0xDE:0xAD:0xBE:0xEF:0x01:0x00:0x00:0x01`
- **Instruction Code**: `0x01` (INS_HELLO_WORLD)

### APDU Protocol

#### Selection
- **Command**: SELECT APDU (ISO 7816-4)
- **CLA**: `0x00`
- **INS**: `0xA4`
- **Response**: `0x9000` (success)

#### Hello World Instruction
- **Command**: 
  - CLA: `0x00`
  - INS: `0x01`
  - P1/P2: Any value
  - Data: Empty
- **Response**: "Hello World" ASCII bytes (11 bytes) + `0x9000`

### APDU Response Format
```
Byte Stream: 0x48 0x65 0x6C 0x6C 0x6F 0x20 0x57 0x6F 0x72 0x6C 0x64 0x90 0x00
ASCII Text:  H    e    l    l    o    SP   W    o    r    l    d    (SW)
```

## Build Process

### Prerequisites
- Java 1.8+ (tested with OpenJDK 21)
- Apache Ant (for ant-javacard)
- Javacard SDK 3.1.0

### Compilation
```bash
javac -cp <javacard-api.jar> -d build/classes src/main/java/HelloWorldApplet.java
```

### CAP File Generation
The CAP file conversion is handled by ant-javacard (requires Javacard SDK tools).

### Build Artifacts
- `build/classes/helloworld/HelloWorldApplet.class` - Compiled bytecode
- `build/HelloWorld.jar` - JAR archive
- `dist/HelloWorld.cap` - Final CAP file (when ant-javacard is used)

## Testing

### Manual APDU Testing
Using a card reader/simulator, send:

**SELECT applet**:
```
00 A4 04 00 08 DE AD BE EF 01 00 00 01
```
Response: `9000`

**Hello World**:
```
00 01 00 00
```
Response: `48656C6C6F20576F726C649000` (Hello World + 9000)

### Verification
- Compiled class: `build/classes/helloworld/HelloWorldApplet.class`
- Size: ~1.2 KB
- No compilation errors/warnings (except Java version deprecation notes)

## Technical Notes

### APDU Handling
- Standard ISO 7816 SELECT command support
- Custom instruction (0x01) for Hello World function
- Proper exception handling (SW codes):
  - `9000` - Success
  - `6E00` - Class not supported
  - `6D00` - Instruction not supported

### Memory Considerations
- Message stored as static byte array in ROM
- Uses Javacard Util.arrayCopy() for safe memory operations
- Minimal memory footprint

### API Usage
- Extends `javacard.framework.Applet`
- Implements `APDU` command processing
- Uses ISO7816 constants for APDU offsets

## Build Commands

### Using Apache Ant
```bash
ant build        # Build the applet
ant clean        # Clean build artifacts
ant help         # Show help
```

### Manual Build
```bash
mkdir -p build/classes dist
javac -cp <api.jar> -d build/classes src/main/java/*.java
```

## References
- Javacard Specification 3.1.0
- ISO 7816-4 (Smart Card APDU Protocol)
- ant-javacard Tool: https://github.com/martinpaljik/ant-javacard

## Status
✓ Project structure created
✓ Applet implementation complete
✓ Source code compiles successfully
✓ Ready for CAP file generation and deployment
