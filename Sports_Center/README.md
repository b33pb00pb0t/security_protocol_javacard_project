# Sports Center JavaCard Template

This folder is the clean JavaCard template for the project. It is intentionally small and is meant to prove that the toolchain works before the real security protocol is added.

## What is here
- `ant-javacard.jar`: the Ant task that builds JavaCard CAP files.
- `build.xml`: the Ant build file for compiling and packaging the applet.
- `build.sh`: a helper script that passes your local JavaCard SDK path into Ant.
- `src/main/javacard`: the JavaCard source tree.

## Local mode
This template expects a JavaCard SDK installed on your machine. Point the build at it with either:
- `JC_HOME=/path/to/javacard-sdk`
- `ant -Djc.home=/path/to/javacard-sdk build`

The SDK should contain `lib/api_classic.jar`.

## Build
```bash
cd Sports_Center
export JC_HOME=/path/to/javacard-sdk
ant -Djc.home="$JC_HOME" build
```

Or use the helper script:
```bash
cd Sports_Center
./build.sh /path/to/javacard-sdk
```

## Hello World APDU flow
1. SELECT the applet by AID.
2. Send `CLA=00, INS=01, P1=00, P2=00`.
3. The applet returns `Hello World` and status word `9000`.

## AIDs
- Package AID: `DEADBEEF01`
- Applet AID: `DEADBEEF0100000001`

These are test values for local development only.
