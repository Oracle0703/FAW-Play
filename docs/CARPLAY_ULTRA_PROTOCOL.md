# CarPlay Ultra protocol integration

The RCS, CAF, OPACK, and UI Sync envelope implementation is adapted from the public xcertplay
`iAP2-rewrite` branch at commit `e021581f1cdcebc495e5a81aae1bef5154848657`:
https://github.com/shilapi/xcertplay/tree/e021581f1cdcebc495e5a81aae1bef5154848657

Upstream and this project use the GPL-3.0 license in the repository root. The imported source is
under `shared/src/main/java/com/shilapi/xcertplay/airplay/rcs/`, together with the Ultra runtime,
provisioning, and feature negotiation files. The two upstream CAF/UI Sync codec test files retain
the protocol examples from that revision. There is no imported synthetic fallback provisioning.

The library provides encrypted framing, APTransport packages, binary plist/OPACK messages,
CAF transaction correlation and explicit plugin dispatch. The HS5 production profile now adds
a real vehicle plugin, UI Sync command executor, local layout resources and renderer, and a lease
on the existing OEM map arbitration path. Unknown operations fail instead of returning fabricated
success or substituting zero for unavailable values. See HS5_CAF_ADAPTER.md and HS5_UI_SYNC_ADAPTER.md.

`AirPlayUltraReadiness.ready` records six independent verification results and remains false
until actual evidence exists. The default VERIFIED_ONLY mode stays closed. An explicit
EXPERIMENTAL mode permits first-phone validation only with all five local implementations,
registered dispatch handlers, valid sidecars and phone negotiation. It never promotes verification
flags. The HS5 setting opts into this mode on the next connection; classic CarPlay keeps its
existing path when the setting is off. Only vehicleStateProtocol and uiSync are provided: no fake
fileTransfer, sessionManagement, mainBuffered or videoPlayback support is advertised.

Each AirPlay session must construct fresh provisioning handlers and a fresh runtime. Runtime
objects must not be serialized or shared between sessions. RCS listeners are keyed by the host's
distinct stream identity; a type-130 teardown closes only the selected stream. Pass the control
session's peer address as `expectedPeerAddress` when opening an RCS stream. A dual-stack listener
accepts one peer and cannot replace an existing connection with a second address-family socket.
Closing a stream immediately closes its sockets. Its existing receive worker delivers the
serialized close callback after any active application callback, so application code cannot
block the UI/control thread during transport close. An unattached handler has not started its
lifecycle and receives no stream callbacks.

Local changes include bounded pending messages, single-peer lifecycle and resource cleanup,
payload-free transport/CAF diagnostics, incomplete-frame EOF detection, OPACK depth/node/input
limits, the four-byte OPACK data-length encoding correction, and separate inbound/outbound CAF
transaction correlation. These are transport correctness checks, not evidence of iPhone or
vehicle end-to-end compatibility. The corresponding unit tests use loopback sockets and synthetic
wire values. They do not operate a vehicle.
