# HS5 UI Sync adapter

This adapter turns the upstream UI Sync codec into a per-connection protocol handler and a local
HS5 presentation target. The source reference remains xcertplay iAP2-rewrite commit
`e021581f1cdcebc495e5a81aae1bef5154848657`.

`CarPlayUiSyncRcsFactory` registers for `CarPlayClusterControl`. It waits for the phone's reset and
creates a fresh target only after the stream opens. Close/failure makes the target inactive.
`CarPlayUiSyncSession` applies a command through the host before issuing its command ACK. It detects
changed duplicate packets, avoids duplicate side effects, honors noAck, bounds duplicate history and
the outbound ACK window, and never logs the vehicle ID or payload.

The development policy expires an incomplete reset after 15 seconds and a command awaiting ACK
after 10 seconds. Both incoming packets and the existing transport receive worker's idle polls check
these deadlines. Expiry closes the UI Sync connection, releases its local presentation and does not
resend commands. Host/application and send exceptions also release presentation without success ACKs.

The factory's optional `onReady(session)` callback runs once when a reset handshake enters READY.
An HS5 host can then call `session.requestLocalLayout(...)` to request its own initial overview layout using
the recovered `requestLayout` fields. No unconfirmed child key is added to `uiSyncInfo` for this.

For a local initial layout the host uses `session.requestLocalLayout(...)`: it requires the
`requestLayout` command, applies the layout to the local target first, and sends only after actual
host acceptance. It releases the presentation if sending fails. Other outbound commands use
`command(...)`, preserving their directional meaning without automatically applying them locally.

The **experimental reciprocal-reset policy** is an explicit development interoperability choice.
The upstream source confirms the envelope, command names and four session-state names, but provides
no executable sequencing rules. Here a remote reset is answered with reset and waits for
resetComplete; a locally initiated reset waits for the peer reset and answers resetComplete.
Packet numbers are monotonic and acknowledgement numbers are cumulative. This policy is tested as
an implementation contract; these tests do not establish target iPhone interoperability. The policy
and state machine can be changed when an actual phone exchange shows a different behavior.

Experimental handshake permission is distinct from verification. The default
`AirPlayUltraNegotiationMode.VERIFIED_ONLY` remains closed until all six readiness evidence flags are
true. An explicitly selected `EXPERIMENTAL` profile may negotiate before a first phone test when its
five `AirPlayUltraImplementation` declarations describe implemented vehicle/UI/assets/renderer/
arbitration paths, and its requested features have registered runtime handlers. The six verified
flags remain false until actually validated. Existing sidecar, geometry, phone-proposal and event-port
checks still apply; a mode switch alone cannot enable an empty implementation.

The target supports the recovered `requestLayout` fields (`layout`, `activeComponents`,
`fadeOutOldLayout`), focus tokens, appearance/preference, UI configuration, drive mode and dynamic
layout assignments. It brackets appearance and layout changes locally. A presentation host must
accept the state before it becomes current. Unknown fields, unknown layout/component IDs and
commands without a recovered executable schema fail explicitly; no invented success payload is sent.
Opaque UI configuration data is copied and passed to the host, never interpreted as executable code.

The two 1920×720 layouts use Autunn-owned IDs:

| ID | Local components |
| --- | --- |
| `autunn.hs5.cluster.navigation.v1` | navigation |
| `autunn.hs5.cluster.overview.v1` | navigation, speed, gear, vehicleStatus |

Their version is `hs5-2022-3212-v1`. These describe inputs for the local host and its assets; they are
not copied Apple/OEM layouts or a claim that the phone already recognizes the IDs. The actual host
owns composition and OEM warning/display arbitration. This model does not replace OEM telltales or
claim a self-drawn display is the native iPhone Ultra renderer.
