# JavaCard Membership System - Implementation and Hardware Guide

## 1. Overview

This project implements a smartcard membership system for a sports recreation
center. It supports both an in-memory JCardSim card and a physical JavaCard
through the same Swing frontend and backend policy services.

The access model has two levels:

- Tier 1 performs certificate validation and challenge-response authentication.
  It has no applet-side daily counter.
- Tier 2 performs mutual authentication and enforces a maximum of two accesses
  per date. The date, daily counter, and transaction counter are persistent
  applet state. Successful Tier 2 responses now return a signed receipt.

Membership records, phone numbers, package types, revocation records, audit
events, and access-terminal snapshots are stored by the host application.

## 2. Architecture

The primary application path is:

```text
Swing GUI
  -> ConnectedTerminalService
  -> CardGateway
       -> JCardSimGateway
       -> HardwareCardGateway
  -> MembershipApplet
```

`ConnectedTerminalService` owns membership policy, CSV persistence, audit
logging, and offline terminal snapshot checks. `CardGateway` isolates card
transport details so the same frontend and service logic works with JCardSim
and real PC/SC hardware.

`JCardSimGateway` creates one in-memory applet session per member ID.
`HardwareCardGateway` connects to a PC/SC reader with `javax.smartcardio`,
selects the installed applet, and sends the same APDUs to a physical card.

The classes under `src/terminals` are standalone low-level hardware demos.
They are not used by the GUI and do not provide the complete backend/offline
policy checks implemented by `ConnectedTerminalService`.

## 3. Repository File Guide

The following tables describe every maintained project file. Bundled
third-party SDK contents and generated output are grouped separately.

### Root Files

| File | Purpose |
| --- | --- |
| `README.md` | Short project entry point with the main build and run commands. |
| `IMPLEMENTATION_AND_HARDWARE_GUIDE.md` | Complete architecture, protocol, hardware, and verification guide. |
| `build.xml` | Ant build definition for the CAP, host classes, simulator regression, and hardware smoke test. |
| `run_project.sh` | Git Bash helper that builds and launches simulator, hardware, or smoke-test modes. |
| `.gitignore` | Excludes generated builds, test output, keys, CAP files, and local artifacts. |
| `.gitattributes` | Defines repository text-file handling. |
| `ant-javacard.jar` | Ant task used to convert and verify the JavaCard CAP. |
| `members.csv` | Persistent backend membership records. |
| `blocked_cards.csv` | Persistent card revocation/block list. |
| `audit_log.csv` | Persistent application authentication and operation audit events. |

### Applet

| File | Purpose |
| --- | --- |
| `src/applet/MembershipApplet.java` | JavaCard applet implementing provisioning, activation, blocking, Tier 1, Tier 2, certificate retrieval, persistent state, and atomic updates. |
| `src/applet/ProtocolConstants.java` | Shared role, operation, certificate, APDU length, nonce, and receipt constants used by applet and host code. |

### Backend

| File | Purpose |
| --- | --- |
| `src/backend/ApduDateCodec.java` | Converts `LocalDate` values to and from the applet's four-byte BCD date format. |
| `src/backend/AuditEvent.java` | Immutable representation of one audit-log row. |
| `src/backend/AuditLogger.java` | Audit logging interface used by authentication and terminal services. |
| `src/backend/BlockListRepository.java` | Abstraction for querying and updating revoked card IDs. |
| `src/backend/BlockListSnapshot.java` | Immutable synchronized view of the block list. |
| `src/backend/CardGateway.java` | Shared card-transport interface used by both simulator and physical-card modes. |
| `src/backend/CardId.java` | Normalizes decimal or hexadecimal member IDs into four-byte card IDs. |
| `src/backend/CsvAuditLogger.java` | Writes and reads audit events in `audit_log.csv`. |
| `src/backend/CsvBlockListRepository.java` | Persists blocked cards in `blocked_cards.csv`. |
| `src/backend/CsvMemberRepository.java` | Persists membership records and lifecycle changes in `members.csv`. |
| `src/backend/HardwareCardGateway.java` | PC/SC implementation that selects and exchanges APDUs with a physical JavaCard. |
| `src/backend/JCardSimGateway.java` | In-memory implementation that creates one JCardSim applet session per member ID. |
| `src/backend/MemberRecord.java` | Immutable membership policy record containing status, expiry, package, phone, and timestamps. |
| `src/backend/MemberRepository.java` | Abstraction for storing and retrieving membership records. |
| `src/backend/NoOpAuditLogger.java` | Audit logger used where persistence is intentionally not required. |
| `src/backend/TerminalOfflineCache.java` | Builds and stores the access terminal's local active/block-list snapshot. |
| `src/backend/TerminalOfflineSnapshot.java` | Immutable access-policy snapshot used during check-in. |
| `src/backend/TerminalSyncService.java` | Produces synchronized block-list snapshots for terminals. |
| `src/backend/Tier2ReceiptVerifier.java` | Reconstructs and verifies the signed Tier 2 receipt before host access is granted. |

### Swing Frontend

| File | Purpose |
| --- | --- |
| `src/frontend/Main.java` | Swing application entry point; selects simulator or `--hardware` mode. |
| `src/frontend/AppFrame.java` | Creates repositories, gateway, services, and role-specific Swing panels. |
| `src/frontend/AuthService.java` | Maps demo passwords to Master, Admin, and Access roles and audits login attempts. |
| `src/frontend/TerminalService.java` | Frontend-facing interface for membership and access operations. |
| `src/frontend/ConnectedTerminalService.java` | Connects GUI actions to backend policy, offline snapshots, auditing, and the selected card gateway. |
| `src/frontend/LoginPanel.java` | Role login screen. |
| `src/frontend/MasterPanel.java` | GUI for card initialization, personalization, certificate installation, and issuer data. |
| `src/frontend/AdminPanel.java` | GUI for activation, deactivation, renewal, blocking, and status checks. |
| `src/frontend/AccessPanel.java` | GUI for terminal synchronization and Tier 1/Tier 2 check-ins. |

### Standalone Terminal Demos

| File | Purpose |
| --- | --- |
| `src/terminals/BaseTerminal.java` | Shared PC/SC connection, AID selection, certificate, and key helpers for standalone demos. |
| `src/terminals/MasterTerminal.java` | Standalone physical-card provisioning demonstration. |
| `src/terminals/AdminTerminal.java` | Standalone physical-card activation and blocking demonstration using authenticated admin APDUs. |
| `src/terminals/OpenAccessTerminal.java` | Standalone Tier 1 APDU and certificate-verification demonstration. |
| `src/terminals/ControlledAccessTerminal.java` | Standalone Tier 2 APDU and terminal-certificate demonstration. |

### Tools and Tests

| File | Purpose |
| --- | --- |
| `src/tools/HardwareSmokeTest.java` | Safe SELECT-only hardware check plus explicitly guarded physical-card test commands. |
| `src/tools/SimulatorRegressionTest.java` | Automated simulator service-flow and negative APDU regression suite. |
| `simulator/RunMembershipSimulator.java` | Interactive raw-APDU simulator lifecycle demonstration. |

### Bundled Dependencies and Generated Data

| Path | Purpose |
| --- | --- |
| `lib/bcprov.jar.jar` | Bouncy Castle provider used by standalone terminal cryptography. |
| `lib/jcardsim-2.2.2-all.jar` | JCardSim runtime used by simulator mode. |
| `util/gp/gp.jar` | GlobalPlatformPro tool for listing, installing, and managing card applications. |
| `util/jcardsim/*` | Additional bundled JCardSim versions, examples, and configuration; not used by the main build. |
| `util/java_card_kit-2_2_1/*` | Bundled JavaCard SDK, APIs, converter, verifier, documentation, and vendor samples. |
| `build/` | Generated host classes, card classes, generated applet source, and CAP output. |
| `hardware_keys/` | Locally generated hardware-mode master and terminal credentials. |
| `master_public.key` | Locally generated public key used by standalone terminal demos. |
| `target/` and `app/target/` | Generated test/build output from older Maven-based runs; not part of the Ant application path. |

## 4. Build Instructions

Requirements:

- JDK 8 or a compatible JDK capable of compiling Java 8 host code
- Apache Ant
- Included JavaCard 2.2.1 kit and project libraries

Build and verify the CAP plus host application:

```powershell
ant clean build-cap build-host
ant simulator-regression
```

The generated installation artifact is:

```text
build/cap/applet.cap
```

The build verifies the CAP before reporting success.

## 5. Running the Simulator

Build the host code, then launch the Swing application:

```powershell
ant build-host
java -cp "build/classes-host;lib/*" frontend.Main
```

The simulator is the default mode. Simulated card sessions exist only for the
current application process, while CSV membership, block-list, and audit data
persist between runs.

GUI demo passwords:

| Role | Password |
| --- | --- |
| Master | `master123` |
| Admin | `admin123` |
| Access | `access123` |

The interactive raw-APDU lifecycle demo is also available:

```bash
./run_project.sh --simulator
```

## 6. Running Hardware Mode

Hardware mode requires:

- A PC/SC-compatible smartcard reader
- A JavaCard containing the membership applet
- Host credentials matching the keys used to provision that card

Launch the Swing application:

```powershell
ant build-host
java -cp "build/classes-host;lib/*" frontend.Main --hardware
```

The first hardware run creates demo RSA keys under `hardware_keys/`. These
files must be retained to authenticate cards provisioned by that host. They are
ignored by Git and must not be treated as production key storage.

Use `CARD_READER` or `-Dcard.reader=...` to choose a reader when multiple
readers are connected. Use `-Dcard.wait.ms=30000` to change the card wait
timeout.

## 7. AID Configuration

Canonical AIDs used by the current CAP:

| Item | AID |
| --- | --- |
| Package | `A00000010203` |
| Applet | `A0000001020301` |

An older physical installation may use legacy applet AID
`A000000001020302`. The host can select it without rebuilding:

```powershell
$env:CARD_APPLET_AID="A000000001020302"
```

Remove the override to use the canonical AID:

```powershell
Remove-Item Env:CARD_APPLET_AID -ErrorAction SilentlyContinue
```

Inspect installed packages and applets:

```powershell
java -jar util/gp/gp.jar -l
```

Install the canonical CAP only after confirming the correct GlobalPlatform
management keys:

```powershell
java -jar util/gp/gp.jar --install build/cap/applet.cap
```

Installing or deleting an applet modifies the physical card. Never overwrite
an existing applet automatically.

## 8. Hardware Smoke Tests

The Ant hardware smoke target is SELECT-only. It lists readers, waits for a
card, prints the reader and ATR, and selects the configured AID:

```powershell
ant hardware-smoke
```

Direct smoke-test commands other than SELECT require the explicit
`--allow-card-modification` guard:

```powershell
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --tier1 12345

java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345
```

Tier 2 updates the persistent daily counter on success. Provisioning,
activation, and block commands also change persistent card state. Do not run
them automatically against a card whose state must be preserved.

To test Tier 2 with a specific terminal date:

```powershell
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345 --date 20260605
```

Without `--date`, Tier 2 uses `LocalDate.now()`.

## 9. Protocol Security Changes

### Certificate Roles

Certificates now include a signed entity role byte. The signed certificate body
is:

```text
Role(1) || EntityID(4) || RSA_Modulus(64) || RSA_Exponent(3)
```

The body is 72 bytes and the issuer/master signature is 64 bytes, so a full
certificate is 136 bytes:

```text
CertBody(72) || MasterSignature(64)
```

Roles enforced by the current implementation:

| Role | Value | Used for |
| --- | --- | --- |
| `ROLE_CARD` | `01` | Card certificates loaded during personalization and returned by the applet. |
| `ROLE_ADMIN_TERMINAL` | `03` | Authenticated activation and block APDUs. |
| `ROLE_CONTROLLED_ACCESS_TERMINAL` | `05` | Tier 2 terminal certificates. |

`ROLE_MASTER_TERMINAL` and `ROLE_OPEN_ACCESS_TERMINAL` are defined for the
shared protocol, but the current applet does not authenticate a terminal
certificate for Tier 1 or provisioning.

### Authenticated Admin Commands

Admin state-changing APDUs now use challenge-response authentication:

```text
1. Admin terminal -> Card: INS 30
2. Card -> Admin terminal: NC(16)
3. Admin terminal -> Card:
   operationData || AdminCertBody(72) || AdminMasterSignature(64) || AdminSignature(64)
```

The applet verifies that the admin certificate is signed by the master key, has
`ROLE_ADMIN_TERMINAL`, and that the admin signature covers the operation data
and the fresh card nonce `NC`.

Signed operation data:

```text
ACTIVATE: OP_ACTIVATE || CardID || CurrentDate || ExpiryDate
BLOCK:    OP_BLOCK    || CardID
```

The applet appends `NC` internally before verifying the admin signature. Only
after all checks pass does it update persistent card state inside a JavaCard
transaction.

### Signed Tier 2 Receipt

Tier 2 step 2 still sends one short APDU under 255 bytes. After successful
terminal authentication and counter update, the card returns:

```text
ResultCode(1) || DailyCounter(1) || TransactionCounter(2) || CardSignature(64)
```

The card signs:

```text
OP_T2_RESULT
|| CardID
|| TerminalID
|| Date
|| TerminalNonce NT
|| CardNonce NC
|| DailyCounter
|| TransactionCounter
|| ResultCode
```

`JCardSimGateway`, `HardwareCardGateway`, and `ControlledAccessTerminal` verify
this signature before reporting access granted. Successful GUI Tier 2 messages
include the verified receipt hex, so `audit_log.csv` can retain it for
forensics.

## 10. APDU Contract Summary

All proprietary commands use CLA `B0`, P1 `00`, and P2 `00`. Dates are four
BCD bytes in `YYYYMMDD` order.

| INS | Operation | Input format | Output format | Required state | Persistent state change |
| --- | --- | --- | --- | --- | --- |
| `10` | Load card private key | modulus(64) \|\| privateExponent(64) | none | `INITIALIZE` | Yes |
| `11` | Load card certificate | CertCBody(72) \|\| MasterSignature(64) | none | `INITIALIZE` | Yes |
| `12` | Load master public key | modulus(64) \|\| exponent(3) | none | `INITIALIZE` | Yes |
| `13` | Activate card | OP_ACTIVATE(1) \|\| cardId(4) \|\| currentDate(4) \|\| expiryDate(4) \|\| AdminCertBody(72) \|\| AdminMasterSignature(64) \|\| AdminSignature(64) | none | `INITIALIZE` or `INACTIVE` | Yes |
| `14` | Block card | OP_BLOCK(1) \|\| cardId(4) \|\| AdminCertBody(72) \|\| AdminMasterSignature(64) \|\| AdminSignature(64) | none | `ACTIVE` | Yes |
| `20` | Tier 1 check-in | terminalNonce(16) | cardSignature(64) | `ACTIVE` | No |
| `21` | Tier 2 step 1 | terminalNonce(16) | cardNonce(16) \|\| cardSignature(64) \|\| Cert_C(136) | `ACTIVE` | No; stores transient nonces |
| `22` | Tier 2 step 2 | terminalSignature(64) \|\| CertTBody(72) \|\| TerminalMasterSignature(64) \|\| date(4) | resultCode(1) \|\| dailyCounter(1) \|\| transactionCounter(2) \|\| cardSignature(64) | `ACTIVE`, valid step 1/authentication, below daily limit | Yes |
| `30` | Admin challenge | none | adminNonce(16) | Any selected state | No; stores transient nonce |
| `60` | Get card certificate | none | Cert_C(136) | Any state except `BLOCKED` | No |

Common status words:

| SW | Meaning |
| --- | --- |
| `9000` | Success |
| `6700` | Wrong payload length |
| `6982` | Signature or security verification failed |
| `6985` | Lifecycle condition, expiry, or daily limit not satisfied |
| `6D00` | Unsupported INS |
| `6E00` | Unsupported CLA |

Tier 2 physical-card diagnostic status words:

| SW | Meaning |
| --- | --- |
| `6F10` | APDU receive problem |
| `6F11` | Terminal certificate loading problem |
| `6F12` | Master signature verification exception |
| `6F13` | Terminal signature verification exception |
| `6F14` | Date/counter transaction exception |

## 11. Physical JavaCard Issue Fixed

Tier 2 step 2 sends a 204-byte APDU after the certificate role byte was added.
JCardSim can deliver that payload in one receive operation, but a physical
JavaCard may deliver it in multiple chunks and previously failed internally
with `SW=6F00`.

`MembershipApplet.receiveFullIncoming()` now calls `setIncomingAndReceive()`
and continues with `receiveBytes()` until the full `Lc` has arrived. Tier 2
step 2 uses this helper before parsing the 204-byte protocol payload.

## 12. Current Verification Status

Verification status as of June 4, 2026:

| Check | Status |
| --- | --- |
| Host build | Pass |
| CAP build and verification | Pass |
| Simulator connected-service regression | Pass |
| Simulator APDU negative checks for roles/admin authentication | Pass |
| Simulator signed Tier 2 receipt and tamper checks | Pass |
| Hardware reader discovery and SELECT-only smoke | Pass |
| Hardware Tier 1 | Previously passed with canonical applet |
| Hardware Tier 2 | Requires installing the rebuilt CAP before retest |
| Tier 2 daily limit | Pass |

The final cleanup verification reruns the build, simulator regression, and
SELECT-only hardware smoke test. It intentionally does not rerun
state-changing physical-card commands.

## 13. Known Limitations

- Renewal and deactivation are backend policy operations because the applet
  does not define corresponding APDUs.
- Phone number and package type are backend-only and are never sent to the
  card.
- The applet accepts malformed BCD date bytes.
- SHA1withRSA and RSA-512 are project/demo choices and are not suitable for a
  production deployment.
- Tier 1 proves card possession by challenge-response, but the applet does not
  authenticate a signed open-access terminal certificate.
- Hardware demo keys are stored as files under `hardware_keys/`; production
  deployments require protected key management.
- The standalone terminal CLIs are APDU demonstrations and do not implement
  the GUI's complete CSV backend, audit log, or offline snapshot policy.
- `MasterTerminal` generates a new standalone master key per run. Do not use
  it against cards provisioned by `HardwareCardGateway` unless the credentials
  have been deliberately aligned.

## 14. Reproducible Commands

```powershell
# Build CAP and host application
ant clean build-cap build-host

# Run complete simulator regression
ant simulator-regression

# Launch simulator GUI
java -cp "build/classes-host;lib/*" frontend.Main

# Run safe physical reader/SELECT check
ant hardware-smoke

# Launch hardware GUI
java -cp "build/classes-host;lib/*" frontend.Main --hardware

# Inspect installed card applications
java -jar util/gp/gp.jar -l

# Explicit physical Tier 1 test
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --tier1 12345

# Explicit physical Tier 2 diagnostic test
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345
```
