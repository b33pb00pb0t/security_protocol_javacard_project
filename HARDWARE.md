# Physical JavaCard Mode

The simulator remains the default. Physical mode uses `javax.smartcardio`
through `backend.HardwareCardGateway`.

## AID

The project applet AID is:

```text
A0000001020301
```

It is used by `build.xml`, `HardwareCardGateway`, `JCardSimGateway`, and
`BaseTerminal`. The unrelated sample configuration under `util/jcardsim` is
not used by this project.

To inspect a card with GlobalPlatformPro:

```powershell
java -jar util/gp/gp.jar -l
```

To install the generated CAP using the card's GlobalPlatform keys:

```powershell
java -jar util/gp/gp.jar --install build/cap/applet.cap
```

Do not run the install command until the correct card-management keys are
known. GlobalPlatformPro otherwise tries its default test key.

If a card already contains an applet under another AID, override the host AID:

```powershell
$env:CARD_APPLET_AID="A000000001020302"
```

At the time hardware support was added, GP reported the currently inserted
card's older applet instance as `A000000001020302`. Rebuild and reinstall the
CAP to use the canonical `A0000001020301` AID, or use the override while
testing that existing installation.

## Build

```powershell
ant clean build-host
ant clean build-cap build-host
```

`build/cap/applet.cap` is the card-installation artifact.

Run the complete non-hardware simulator regression:

```powershell
ant simulator-regression
```

## Reader And SELECT Smoke Test

The default smoke test lists readers, waits up to 10 seconds for a card,
prints its ATR, and selects the applet without modifying the card:

```powershell
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest
```

Provision and activate a newly installed applet:

```powershell
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --provision-and-activate 1234 20261231
```

Use `CARD_READER` or `-Dcard.reader=...` to select a reader. Use
`-Dcard.wait.ms=30000` to change the card wait timeout.

Provision, activation, Tier 1, and Tier 2 smoke commands require the explicit
`--allow-card-modification` flag. They print a warning before execution.
Provision, activation, and Tier 2 modify persistent card state. The default
smoke test never sends those commands.

To print concise Tier 2 APDU diagnostics:

```powershell
$env:CARD_APPLET_AID="A000000001020302"
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345
```

To send a specific terminal date in the Tier 2 step 2 APDU, append
`--date YYYYMMDD`. This option only affects Tier 2; without it, the terminal
uses `LocalDate.now()`.

```powershell
$env:CARD_APPLET_AID="A000000001020302"
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345 --date 20260605
```

## GUI

Simulator:

```powershell
java -cp "build/classes-host;lib/*" frontend.Main
```

Physical card:

```powershell
java -cp "build/classes-host;lib/*" frontend.Main --hardware
```

`run_project.sh --hardware` also launches hardware mode.

Other Git Bash run modes:

```bash
./run_project.sh --simulator
./run_project.sh --hardware-smoke
```

Hardware mode stores demo RSA-512 private keys under `hardware_keys/` and
writes `master_public.key` for the standalone physical terminals. These files
are ignored by Git. This key storage and SHA-1/RSA-512 are project/demo
limitations and are not suitable for production.

The standalone `ControlledAccessTerminal` now sends the correct `21`/`22`
exchange and persists its terminal key so `terminal_certificate_to_sign.bin`
is stable. It still requires a valid 64-byte master signature for that
certificate before it can complete Tier 2. Terminal-certificate issuance is
outside this hardware-transport change.

The applet has no command for reading its lifecycle state. After restarting
the GUI, hardware mode treats a successfully selected existing card as
potentially active and lets the next APDU determine its real state.

The applet also has no renewal or deactivation APDU. Those operations remain
backend policy only. Reactivating an already-active card is accepted after a
successful Tier 1 proof, but it does not replace the expiry date already stored
on that card.

## APDUs Used By Hardware Mode

All proprietary commands use CLA `B0`, P1 `00`, and P2 `00`.

| INS | Operation | Request | Success response |
| --- | --- | --- | --- |
| `10` | Initialize private key | modulus 64 + private exponent 64 | no data |
| `11` | Load card certificate | card ID 4 + public key 67 + master signature 64 | no data |
| `12` | Load master public key | modulus 64 + exponent 3 | no data |
| `13` | Activate | member ID 4 + current BCD date 4 + expiry BCD date 4 | no data |
| `14` | Block | no data | no data |
| `20` | Tier 1 | terminal nonce 16 | card signature 64 |
| `21` | Tier 2 step 1 | terminal nonce 16 | card nonce 16 + card signature 64 + Cert_C 135 |
| `22` | Tier 2 step 2 | terminal signature 64 + Cert_T 71 + master signature 64 + date 4 | daily counter 1 |
| `60` | Get card certificate | no data | Cert_C 135 |

Expected status words are `9000` for success, `6700` for a wrong payload
length, `6985` for an invalid lifecycle state/expiry/daily limit, `6982` for
failed terminal authentication, `6E00` for the wrong CLA, and `6D00` for an
unsupported INS.

Tier 2 step 2 also returns these diagnostic status words for physical-card
internal exceptions:

| SW | Meaning |
| --- | --- |
| `6F10` | APDU receive/chaining problem |
| `6F11` | Terminal certificate public-key loading problem |
| `6F12` | Exception while verifying the master signature |
| `6F13` | Exception while verifying the terminal signature |
| `6F14` | Date/counter transaction exception |

## Install And Test The Fixed Applet

The currently inserted physical card has the older applet instance
`A000000001020302`. The CAP produced by the current build uses the canonical
applet AID `A0000001020301`.

Build and verify the fixed CAP:

```powershell
ant clean build-cap build-host
ant simulator-regression
```

List the existing card contents before installing:

```powershell
java -jar util/gp/gp.jar -l
```

Install the canonical CAP only after confirming the correct GlobalPlatform
management keys:

```powershell
java -jar util/gp/gp.jar --install build/cap/applet.cap
```

Do not delete or overwrite the legacy applet automatically. Installing,
deleting, provisioning, and activation modify persistent card state.

After installing the canonical CAP, remove the legacy AID override and test
the new applet:

```powershell
Remove-Item Env:CARD_APPLET_AID -ErrorAction SilentlyContinue
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --provision-and-activate 12345 20271231
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345
java -cp "build/classes-host;lib/*" tools.HardwareSmokeTest --allow-card-modification --debug-tier2 12345 --date 20260605
```

Rebuilding under the legacy AID would require deliberately changing the CAP
package/applet AIDs and replacing the already-installed legacy package. That
path is destructive and should only be done after explicitly agreeing which
existing package to delete.
