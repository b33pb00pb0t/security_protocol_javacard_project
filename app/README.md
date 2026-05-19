# Sports Recreation Center - Terminal Frontend

Java Swing frontend for the Sports Recreation Center JavaCard membership demo. The app now uses a connected backend service by default, backed by JCardSim and CSV repositories, so the UI can run the main Master/Admin/Access workflows without a physical smart-card reader.

## Run

Requirements:
- JDK 8
- Maven

From this `app` directory:

```bash
mvn compile exec:java
```

Run tests:

```bash
mvn test
```

Maven may warn that the local JCardSim dependency uses `systemPath`. This is expected for the current course-project layout; the app uses `../lib/jcardsim.jar.jar`.

## Logins

| Password | Terminal |
| --- | --- |
| `master123` | Master Terminal |
| `admin123` | Admin Terminal |
| `access123` | Access Terminal |

## Typical Demo Flow

1. Login as Master with `master123`.
2. Enter a member ID, for example `1234`.
3. Click **Initialize Card** or **Personalize Card**.
4. Logout and login as Admin with `admin123`.
5. Activate the same member ID with an expiry date such as `20991231`.
6. Logout and login as Access with `access123`.
7. Click **Sync Terminals**.
8. Run **Tier 1 Check-In** or **Tier 2 Check-In**.

Member IDs are normalized to 4-byte uppercase hex. For example, `1234` becomes `000004D2`.

## Panels

### Master Terminal

- Provisions an in-memory JCardSim card session.
- Generates card key material and installs certificate/issuer data through the connected service.
- Saves package information in the member CSV backend.

### Admin Terminal

- Activates cards.
- Deactivates and renews membership in backend policy.
- Reports lost/stolen cards and writes them to the block list.
- Reads backend/card status.
- Views the block list.

### Access Terminal

- Runs Tier 1 and Tier 2 check-ins through JCardSim APDUs.
- Uses an offline cache snapshot for active and blocked card policy.
- Requires **Sync Terminals** before access decisions use the latest backend state.
- Shows stale-cache behavior: Admin changes are not visible to Access until the next sync.

## CSV Files

The app uses CSV files for demo persistence:

| File | Purpose |
| --- | --- |
| `members.csv` | Member status, expiry, package, phone, timestamps |
| `blocked_cards.csv` | Lost/stolen/blocked card IDs |
| `audit_log.csv` | Login attempts, terminal actions, check-ins, syncs, denied reasons |

JCardSim card sessions are in memory only. After restarting the app, initialize and activate the card again even if `members.csv` still says the member is active. `Read Card Status` shows both backend status and `AppletActive=true/false`.

## Offline Cache Simulation

Access terminals do not read the live backend repositories during check-in. Instead:

- **Sync Terminals** copies the current active members and block list into a local snapshot.
- Tier 1/Tier 2 check-ins use that snapshot.
- If the cache has not been synced, check-in is denied.
- If Admin changes a member after sync, Access keeps using the old snapshot until sync is pressed again.

This simulates OAT/CAT offline operation from the design document.

## APDU Date Encoding

Date bytes are centralized in `ApduDateCodec`.

Example:

```text
2026-05-19 -> 20 26 05 19
```

This avoids scattering date-byte logic through the app and makes APDU payload tests clearer.

## Current Limitations

- Physical smart-card reader integration is not implemented; this module uses JCardSim.
- Card sessions are not persisted across app restarts.
- Expiry date and package type are backend-only; the applet does not store/enforce them.
- Deactivation and renewal are backend-only because the current applet APDU map has no commands for them.
- Activation is not mutually authenticated at the applet level yet.
- Card-tear and transaction atomicity behavior is not fully implemented.
- Offline cache sync is manual; there is no automatic 6-hour scheduler yet.
- The copied terminal classes under `com.sports.recreation.terminals` are not the production integration path for the Swing UI.
