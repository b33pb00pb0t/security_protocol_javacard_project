# JavaCard Membership System

Smartcard membership system with a Swing frontend, CSV backend policy,
JCardSim support, and physical JavaCard support.

## Quick Start

```powershell
ant clean build-cap build-host
ant simulator-regression
java -cp "build/classes-host;lib/*" frontend.Main
```

Physical-card SELECT-only verification:

```powershell
ant hardware-smoke
```

Hardware GUI:

```powershell
java -cp "build/classes-host;lib/*" frontend.Main --hardware
```

See [IMPLEMENTATION_AND_HARDWARE_GUIDE.md](IMPLEMENTATION_AND_HARDWARE_GUIDE.md)
for architecture, AIDs, APDU formats, physical-card testing, verification
status, and known limitations.
