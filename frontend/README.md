# Sports Recreation Center - Frontend Panel

This is the Java Swing-based frontend application designed to interact with our JavaCard smart card project (`MembershipApplet.java`).

## Overview

The Frontend mimics the behaviour of graphical terminals used by the Sports Center staff to manage member profiles inside their smart cards. 
Currently, the application contains a **Mock Terminal Service**, which simulates card responses, giving us a visual interface to play with while we finish developing the real APDU transmission interface between the applet and our Java PC code.

## Architecture & Panels

When you launch the app, you arrive at the **Login Panel**. Your credentials determine which kind of terminal context you gain access to:

1. **Admin Terminal (`ADMIN` Role)**
   - Used by the gym's front-desk administration.
   - Has permissions to activate cards, deactivate them, renew membership expirations, and report cards as lost/stolen.

2. **Master Terminal (`MASTER` Role)**
   - Used by high-level management or secure issuing machines.
   - Has permissions to format and initialize blank smart cards, personalize them with member packages (e.g., standard, VIP), install certificates, and load issuer data.

## How to build and run

This module is a `Maven` project and needs a `JDK 8`.

1. Ensure you have installed Java 8 (`jdk1.8.0_202`) and set the `JAVA_HOME` environment variable properly to its directory.
2. Install Maven (`choco install maven`).
3. Open your terminal in the `frontend` directory.
4. Execute:
   ```bash
   mvn compile exec:java
   ```

## Development guidelines

- Avoid modifying `MockTerminalService.java` unless strictly necessary for UI testing. Eventually, it will be replaced by a concrete `CardTerminalService` class capable of parsing and sending APDUs toward the `JCardSim` simulator or a physical smart card.
- User Interface changes: `AdminPanel`, `MasterPanel`, and `LoginPanel` extend standard `JPanel`s. The main viewport frame is handled entirely by `AppFrame.java` with a `CardLayout`.