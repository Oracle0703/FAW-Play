# HS5 vehicle adapter — 170 integration build

This build connects the HS5 vehicle model, CAF plugin, executable UI Sync session, local
1920×720 resources/renderer and OEM display lease to fresh production CarPlay sessions.
Settings → diagnostics → “HS5 CarPlay Ultra 实验适配” enables experimental negotiation on
reconnect. It defaults off. No vehicle or iPhone validation has been performed for build 170.
The implementation is a testable integration, not a claim of complete native Apple Ultra parity.
See [protocol integration](CARPLAY_ULTRA_PROTOCOL.md), [CAF adapter](HS5_CAF_ADAPTER.md)
and [UI Sync adapter](HS5_UI_SYNC_ADAPTER.md).

## Firmware boundary

The provider accepts only Android `Build.DISPLAY=3212` with `ltd.qisi.adapterservice` version code
`3167`, version name `20240905-114725`. The reviewed package VDEX matches the previously sampled
vehicle service, SHA-256 `04359538b6abd8a7d6310f3f38eaf944a4ddbeb5153c454bc2e29767ae0efd1d`.
This identifies the service contract, not the entire firmware image.

Writes additionally check the OEM EOL model family (`7901010DD4` / `7901010DD5`). The identifier
is used in memory for this check and is never included in a result, diagnostic or persisted state.
Unsupported firmware, unavailable feedback or unknown seat capability disables the affected write.

## Data and controls

The immutable process-local snapshot contains 31 fields: gear, speed, four doors, trunk, range,
position lights, fuel remaining, total mileage, four tyre pressures, climate power, driver/passenger
temperature, fan, ambient temperature, climate AUTO/sync/compressor/circulation/front defrost,
driver/passenger heat/vent and two front-seat capability flags.

The provider preserves the service's fuel/mileage/tyre values without adding an unverified unit or
scaling them again. Invalid, future, expired and disconnected values remain unknown. No RPM value,
hood index, unsupported light setter or fixed-return OEM stub is synthesized.

Settings → diagnostics → “车辆状态（只读）” shows the snapshot. “空调与座椅” exposes 13 rows:
driver/passenger temperature, fan, five climate switches, circulation and four seat controls.
Temperature commands use the reviewed 17.5–32.5 °C intersection in 0.5 °C steps; fan uses 0–7.
The view rechecks freshness and capability at the click boundary. Opening, refreshing or restoring
the page never submits a command. It does not update the displayed value optimistically.

## Request lifecycle

`HongqiVehicleData.acquire(context)` leases the shared provider; `snapshot()` never performs Binder
work on the caller's thread. `submit(typedCommand, callback)` requires an active lease and returns a
`Closeable` cancellation handle. There is one pending command and one shared Binder worker.

Only the reviewed fixed setter whitelist is exposed. A setter return of 1 means accepted for
dispatch; it is not an ECU acknowledgement. The provider reads the target getter every 300 ms and
returns `CONFIRMED` only if a fresh read strictly after dispatch matches the requested value. This
is state agreement, not proof that this request caused the physical result. The absolute deadline
is eight seconds, including any worker queue delay.

Writes are never persisted, retried or replayed after reconnect. Timeout, disconnect, firmware
replacement and cancellation invalidate pending work. Closing the handle immediately marks the
request cancelled across threads, but cannot undo a Binder command already issued. Stopping or
destroying the page retires its callbacks and releases the lease.

## Ultra session lifecycle

RCS, CAF and UI Sync envelopes have separate session-owned streams, explicit phone feature
negotiation, stream IDs shared with the existing remote-control allocator, per-session seed reuse
protection, and targeted teardown. Handlers attach only after the SETUP response is flushed.
Each resumed CarPlay session constructs a fresh provisioning/runtime instead of reusing handlers
from the previous session.

All six readiness evidence flags stay false. Explicit experimental negotiation checks five
implemented local components and real protocol handlers; it allows a first phone test without
inventing verification results. The factory captures only application context, creates fresh
vehicle/plugin/UI handlers per resume, and isolates old stream close callbacks from new owners.

The initial known-schema requestLayout applies the Autunn overview locally before sending the
same layout command to the phone. The view combines real alternative-screen video with cached
vehicle status. Only confirmed CAF characteristic types are advertised: driver/passenger target
temperature and fan. The local model retains all 31 fields and 13 reviewed controls; that does
not turn their raw Qisi IDs into Apple characteristic IDs. Left/right zone interpretation and
invalid-value presentation remain phone interoperability tests.

The local resources are Autunn-owned vector compositions and a geometry manifest, not an Apple
asset-transfer archive. No file-transfer service is declared. Unsupported layout/configuration,
focus requests and unknown commands fail instead of receiving synthetic success.

## Display ownership and recovery

The presentation borrows the measured OEM map output, without disabling OEM components or
changing SOS telltale layers. Initial display activation requires two distinct fresh P-gear and
zero-speed observations, valid instrument-map authorization, live OEM interfaces/exit observer,
and an applied local layout. A navigation-only layout additionally needs actual video. Reverse,
non-P gear, stale values, OEM view-38 interruptions and hard quit yield the output. The native
request sequence must complete before the window becomes visible. Reentry after an interruption
requires fresh samples. Hard quit stays latched until the lease is replaced.

A closed UI Sync stream, presentation stop or renderer detach removes the local composition and
releases the lease, restoring the prior classic layout/registration. UI commands wait for actual
main-thread acceptance; a posted Runnable alone is never considered success. UI Sync reset and
command-ACK deadlines close an unresponsive flow and restore the local display.

## Acceptance still open

Build and JVM checks validate implementation behavior. The next stationary vehicle/iPhone test
must verify actual feature proposals, UI Sync reset/ACK behavior, phone acceptance of the local
layout ID, CAF zone interpretation, HVAC feedback and OEM interruption/restoration. Extra getters
beyond the historically sampled 16 and new setters require vehicle evidence. No ADB operation is
part of build 170 verification. Driving takeover remains disabled until its display contract is
validated; unrecognized standard property identifiers are never guessed.
