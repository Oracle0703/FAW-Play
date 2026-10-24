# HS5 Ultra local display adaptation

The local UI uses Autunn vector composition at 1920 × 720. It is connected to UI Sync through
`Hs5UltraPresentationHost` and `ClusterMapPresentation`, with resources described in
`common/src/main/assets/hs5-ultra/resources-v1.json`. These are Autunn resources, not an Apple
or Hongqi dashboard asset package.

`autunn.hs5.cluster.navigation.v1` retains full-size video. The overview layout places the same
8:3 video in a 1280 × 480 viewport at (320,120), with local speed, Qisi gear, four-door/trunk,
position-light and climate feedback at the sides. No RPM, fuel unit or speed scaling is invented.
Unknown or older-than-2500 ms readings display unknown; they never become zero or Park.
Overview local components can render before a navigation video stream starts.

The experimental lease authorizes this surface only with an active UI session, applied local
content, two distinct fresh P=0 / speed=0 observations, and the existing native map module plus
exit observer. Moving, R=1, non-P, missing/stale reads, renderer loss and disconnect release it.
This is a stationary integration path, not a claim of complete driving-instrument takeover.

The adapter reuses the already measured OEM protocol: instrument module 8 transaction 10 sends
full-map load 2, system-control module 13 transaction 69 requests (1,0), then full-map load 4.
Pixels stay hidden until those operations return nonnegative acknowledgements. Release sends
load 0 and fullscreen (0,0). Acknowledgement means command acceptance, not independent optical
proof that the SOS compositor displayed pixels. The app does not disable stock components,
replace a Kanzi warning resource, or write a framebuffer / SOS surface-control socket.

Native priority sources are the existing instrument `HardKeyQuit` callback and the existing
Qisi system view 38 listener. LOSS / LOSS_TRANSIENT / unknown releases the Ultra request;
GAIN permits fresh stationary observations to reacquire. HardKeyQuit stays latched for the
lease, so a repeated ready signal cannot fight the driver's native exit. Lease close restores
classic map registration and route policy. Without an Ultra lease, classic navigation behavior
and its original defaults are preserved.

The current SDK implementation of AVM `getAVSStatus` returns constant false, while
`getSwitchScreenRequest` returns -2. Neither is used as proof that a camera is inactive. A real
future camera observation may veto through `updateCamera`; no fabricated all-warning-clear
getter or boolean is supplied. Native SOS warning / ADAS visibility remains owned by the OEM
compositor and requires separate moving-vehicle acceptance before extending this stationary path.

The renderer accepts only its two layout IDs, their actual component subsets, dark/light
appearance, dark/light/system preference with Boolean override, and its resource version or
current layout as UI configuration with no opaque data. A drive mode requires a corresponding
known layout assignment. Unknown layouts, appearance, data, nonnull focus tokens and fade-out
transitions are rejected without mutating the last applied layout.
