# Sports Recreation Center Membership Applet

This folder contains the production-ready JavaCard applet skeleton and build tooling for the Sports Recreation Center Membership System.

## What is here
- `ant-javacard.jar`: the Ant task that builds JavaCard CAP files.
- `build.xml`: the Ant build file for compiling and packaging the applet.
- `build.sh`: a helper script that passes your local JavaCard SDK path into Ant.
- `src/com/sports/recreation`: the JavaCard source tree.
- `simulator/RunMembershipSimulator.java`: local JCardSim harness (JCardSim 2.2.2).

## Local mode
This template expects a JavaCard SDK installed on your machine. Point the build at it with either:
- `JC_HOME=/path/to/javacard-sdk`
- `ant -Djc.home=/path/to/javacard-sdk build`

The SDK must contain `lib/api_classic-3.1.0.jar`.

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

## Simulator
The local simulator uses JCardSim 2.2.2.
```bash
cd Sports_Center
./run-simulator.sh
```

## Membership APDU flow (skeleton)
1. SELECT the applet by AID.
2. Send `CLA=B0, INS=10, P1=00, P2=00` to invoke initialization.
3. The current skeleton returns `SW=6A81` (function not supported) until handlers are implemented.

## AIDs
- Package AID: `A00000010203`
- Applet AID: `A0000001020301`
