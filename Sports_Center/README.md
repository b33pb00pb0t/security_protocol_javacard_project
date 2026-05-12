# Smart Card Membership System

A highly secure, offline smart card gym membership platform built on JavaCard 2.2.1. The design uses a custom PKI with 512-bit RSA and a split-logic model: the smart card is the immutable cryptographic anchor, while terminals enforce business rules (blocklists, active lists, counters).

## Section 1: Applet Implementation (Work Completed)

### Hardware optimizations and platform constraints
- **APDU payload splitting**: legacy JavaCard 2.2.1 limits the standard APDU data field to 255 bytes. Factory provisioning is split into three commands to stay within this limit:
	- Private key provisioning (128 bytes)
	- Certificate loading (135 bytes)
	- Master public key loading (67 bytes)
- **RAM / buffer reuse**: cryptographic signatures are written into offset regions of the shared APDU buffer to avoid extra allocations. The card challenge `N_C` is stored in transient RAM (`CLEAR_ON_DESELECT`) to prevent EEPROM wear and to guarantee the nonce is wiped on deselect.
- **Lifecycle states**: the applet enforces an explicit lifecycle that is stored in EEPROM:
	- **INITIALIZE**: factory provisioning only
	- **ACTIVE**: card can authenticate and check in
	- **INACTIVE**: soft-disabled, can be reactivated by the Admin Terminal
	- **BLOCKED**: irreversible lockout (loss/theft)

### APDU command map

| INS | Command | Terminal | Purpose |
| --- | --- | --- | --- |
| 0x10 | Initialize Key | Master Terminal (MT) | Provision card private key (128 bytes) |
| 0x11 | Load Cert | Master Terminal (MT) | Load card certificate `Cert_C` (135 bytes) |
| 0x12 | Load Master Key | Master Terminal (MT) | Load master public key `PK_M` (67 bytes) |
| 0x13 | Activate | Admin Terminal (AT) | Activate card and set initial date/counter |
| 0x14 | Block | AT / Terminals | Irreversibly block a card |
| 0x20 | Check-In T1 | Open-Access Terminal (OAT) | Tier 1 challenge-response (sign `N_T`) |
| 0x21 | Check-In T2 Step 1 | Controlled-Access Terminal (CAT) | Mutual auth step 1: return `N_C`, `sigma_1`, `Cert_C` |
| 0x22 | Check-In T2 Step 2 | Controlled-Access Terminal (CAT) | Mutual auth step 2: verify `Cert_T` and `sigma_2`, return counter |
| 0x60 | Get Cert | Any Terminal | Retrieve `Cert_C` for offline verification |

## Section 2: Terminal Integration (Work To Be Done)

The terminal applications are external (Java/C++ with Bouncy Castle). They implement policy and verification logic while the card only handles cryptographic proofs.

### Master Terminal (MT)
- Factory environment only.
- Generates 512-bit RSA key pairs for cards.
- Builds and signs `Cert_C` using the master private key `SK_M`.
- Sends the three provisioning APDUs in sequence: private key, certificate, master public key.

### Admin Terminal (AT)
- Front desk administrative control.
- Reads `Cert_C` (INS 0x60), extracts `ID_C`, and links to customer records.
- Sends **Activate** (INS 0x13) with `ID_C` and current date.
- Maintains `block_list.csv`, pushes block commands (INS 0x14).

### Open-Access Terminal (OAT)
- Tier 1 turnstiles with offline sync.
- On tap:
	- Read `Cert_C`, extract `ID_C`, check blocklist and active list.
	- If valid, send a 16-byte `N_T` (INS 0x20).
	- Verify the card signature with `PK_C` embedded in `Cert_C`.

### Controlled-Access Terminal (CAT)
- Tier 2 VIP gates with mutual authentication.
- On tap:
	- Send `N_T` (INS 0x21).
	- Receive `N_C`, `sigma_1`, and `Cert_C`.
	- Sign `N_C || CurrentDate` using `SK_T` and send `Cert_T` + signature (INS 0x22).
	- Read the returned daily counter to open the gate.

## Section 3: Simulator & Testing

`RunMembershipSimulator.java` uses JCardSim to emulate the JavaCard runtime. It performs an end-to-end integration test, including:
- Provisioning the card with 512-bit keys and certificates.
- Executing the Tier 2 mutual-authentication sequence with the full 203-byte APDU layout.
- Verifying that the cryptographic chain of trust completes successfully (SW=9000).

### Build and run
```bash
cd Sports_Center
export JC_HOME=/path/to/javacard-sdk
ant -Djc.home="$JC_HOME" build
./run-simulator.sh
```

### AIDs
- Package AID: `A00000010203`
- Applet AID: `A0000001020301`
