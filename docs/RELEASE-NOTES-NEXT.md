# DiPlay next release notes

Autunn Play 0.3.6-AUTUNN24 (155, restore CarPlay after a retained vehicle-screen state):

- Treat `modesChanged` as an acknowledgement of resource ownership. A retained `screen=VEHICLE`
  state received while reopening the existing Host no longer moves that Host back to the desktop.
- Keep the foreground request pending until the phone acknowledges `screen=IPHONE`; only a later
  vehicle takeover after an acknowledged iPhone owner returns the Host to the vehicle UI.
- Add a lifecycle regression covering stale vehicle ownership, screen restoration, and a later
  legitimate vehicle takeover.

Changes through DiPlay 0.2.12 are documented in [0.2.12 release notes](RELEASE-NOTES-0.2.12.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Add future unreleased changes here.

Autunn Play 0.3.5-AUTUNN23 (154, restore the main screen after returning to the vehicle):

- Keep the live CarPlay session when the main video producer ends normally during an
  intentional vehicle-screen handoff. Bind the expected end to that producer before opening
  HOME, and cover EOF racing with stream TEARDOWN. Revoke its input/frame readiness and
  release only its renderer; instrument and audio streams retain their own lifetimes.
- Route explicit launcher entry through the live session and request the main screen without
  requiring its withdrawn video stream. Select once per foreground entry/session; automatic
  entry, input and frame presentation still use their existing readiness checks.
- Distinguish clean record-boundary EOF from truncated, oversized or unauthenticated video
  records. Unexpected main EOF and transport errors still disconnect; old producer callbacks
  cannot stop the replacement. Entering the screen does not select audio or force playback.
- Based on two captured 153 vehicle failures. Local regressions exercise the affected paths;
  repeated return/reentry and media continuity still require HS5 3212/3222 vehicle acceptance.

Autunn Play 0.3.4-AUTUNN22 (153, instrument output during license verification):

- Keep a committed, verified entitlement available while local verification or normal renewal
  is in progress. Evaluate signed expiry and monotonic clock integrity on every read instead
  of treating a one-second snapshot age as a loss of authorization.
- Replace a renewed ticket only after validation and persistence. Retain immediate revocation,
  device/app/signature checks, real expiry, failed local verification, and stale-publication guards.
- Record entitlement changes and slow local verification without logging tickets or identities.
- Vehicle logs from 152 show map exit/reload while navigation remains active. This change fixes
  a matching source-level cause; sustained navigation still requires vehicle acceptance.

Autunn Play 0.3.3-AUTUNN21 (152, CarPlay resource coordination):

- Track acknowledged screen/main-audio ownership independently. Entering the screen does
  not select the audio source; duplicate mode notifications do not reopen the host.
  Forward OEM temporary screen/audio interruptions as borrow/return and permanent choices
  as take/release. Gate main audio, microphone and screen input by the acknowledged owner.
- Restart an exited renderer from an active stream's next packet. Bound consecutive failures
  without sustained output to two replacements, and reject packets after stream end.
- Withdraw discovery and new-session admission on wireless link invalidation while retaining
  authenticated TCP until a real transport failure. Keep the existing peer detection bounds.
- Persist separate per-phone screen, audio and playback choices. Teardown cannot overwrite
  the saved playing intent; remembered vehicle audio remains respected. Full automatic playback
  across trips remains pending verified OEM source continuity while the app was offline.
- Exclude the main-display-only safe-area flag from secondary displays. Preserve physical
  Siri down/up and classified OEM long-press completion, with session-bound release.
- Validation records accompany the local build. HS5 3212 and 3222 runtime acceptance remains
  separate from local compilation, tests and lint; no claim of MFi certification.

Autunn Play 0.3.2-AUTUNN20 (151, background connection progress):

- Forward primary stream acceptance and first decrypted picture from the real TCP service
  listener to the startup owner, including reception before any foreground Surface exists.
  Forward runtime, navigation and instrument progress explicitly at the same boundary.
- Preserve the 150 launcher-reopening and old-service-cleanup fixes. Existing phase ceilings
  and retry limits remain; received progress completes the stage immediately.
- Add a regression through a real service, TCP socket and RTSP SETUP. It reproduced the
  missing primary readiness callback in the 150 compiled classes before the fix.
- Build signed releases from clean shared/common/mobile outputs with build-cache reuse
  disabled, so changed listener interfaces cannot leave stale generated delegations.
- Android phone runtime validation and HS5 3212/3222 vehicle acceptance are separate checks.

Autunn Play 0.3.1-AUTUNN19 (150, launcher reopening fix):

- A fresh foreground launcher entry can start a new automatic use after removal from Recents
  ended the previous use, including same-boot process recreation. Keep automatic connection
  settings, selected-phone admission, parking, resource cleanup and in-app manual disconnect
  authoritative. Ordinary service/phone events and explicit settings links cannot reopen it.
- Decide whether to stop the service from its current use after task-removal cleanup finishes,
  so an old completion cannot stop a service now serving a new foreground request.
- Phone logs on 149 reproduced the blocked-launch path twice. The subsequent manually started
  successful connections reached authenticated main frames in 7.687 and 9.566 seconds. These
  Android phone observations do not establish HS5 Android 9 timing or firmware acceptance.

Autunn Play 0.3.0-AUTUNN18 (149, vehicle acceptance build):

- Separate runtime settings, Bluetooth selection, connection status, media decoding, audio
  rendering and focus, authorization requests, and update storage from their UI/orchestration
  owners. See [client architecture](../CLIENT_ARCHITECTURE.md) for ownership and validation.
- Give a selected, paired iAP2-capable phone one automatic bootstrap opportunity per vehicle
  use even before the native Bluetooth profiles connect. Persist the attempt and wait for an
  arrival event after failure; retain manual stop, parking and stale-generation barriers.
- Recover Android 9 unanswered channel commands as transient platform failures, invalidate
  lost P2P groups immediately, and remember only confirmed creation strategies. Preserve
  unknown actual frequencies. Confirm the actual published address family after authenticated
  first-frame success and resample addresses on every new interface.
- Recover HUD and instrument modules independently. Distinguish business rejection from a
  dead Binder, advance map loading only after acknowledgement, and preserve native map mode
  across a brief stream interruption with a bounded ten-second loss watchdog.
- Preserve the user's debug-log choice across bootstrap and send native output transitions
  through the existing redacted, bounded diagnostic store.
- Retain established protocol encodings, media algorithms, signing identity, activation and
  settings compatibility, update signature validation and online installation authorization.
  No server change is required. Native Bluetooth auto-connect, whole-cluster blackout,
  connection latency and audible continuity still require separate 3212/3222 vehicle tests.

Autunn Play 0.2.61-AUTUNN4 (135): navigation underrun recovery and bounded playback of queued guidance at stream end. See [audio changes](RELEASE-NOTES-AUTUNN4.md).

Autunn Play 0.2.73-AUTUNN16 (147, test release):

- Build the final preparation page once and keep its live status, recovery actions and theme updates.
- Confirm the current codec/Surface's first presented picture from MediaCodec's frame-rendered
  callback. Reject old binding callbacks and old timestamps after a Surface switch. For drivers
  that omit callbacks, use explicitly logged submitted-output compatibility evidence after a
  bounded 500 ms grace period; this is not a connection readiness delay.
- Fade the preparation cover out over 160 ms once the current primary picture is ready. Cancel
  stale reveals on Surface/session changes or lifecycle loss; preserve the foreground-entry
  confirmation and retry policy independently of picture readiness.
- Let the main reveal finish before creating instrument windows, with a two-second ceiling and
  lifecycle release so missing main output cannot indefinitely suppress the instrument display.
- Give the CarPlay Host a short alpha window transition and suppress its redundant starting
  preview. Settings and other pages retain their own themes.
- Target-head-unit entry smoothness still requires vehicle acceptance.

Autunn Play 0.2.72-AUTUNN15 (146, test release):

- Advance preparation and retry gates from state-save, vehicle-candidate cancellation and
  actual resource-release completion events. Retain a valid prepared stack during a same-use
  save; start known-required teardown alongside backoff while preserving release barriers.
- Bound wireless identification to 10 seconds, authentication to 30 seconds and the first
  StartSession request to 20 seconds. These are failure ceilings, not startup delays; the
  established runtime control tunnel keeps its independent lifetime.
- Protect cold startup after the first external TCP with session/main-screen/picture progress
  stages and a 30-second aggregate ceiling. Use a current decrypted primary picture as
  reception evidence even without a display Surface; configuration and alternate/stale streams
  cannot satisfy it. Keep fast reconnect's existing independent policy.
- Keep one 120-second startup budget across outer retries and same-boot process recovery.
  Repeated Bluetooth arrivals cannot replenish an unfinished attempt. A valid primary picture
  completes it; explicit retry and a new vehicle use retain their own reset semantics.
- Share automatic Host entry submission, foreground confirmation and bounded retries between
  the visible software page and background service. Main-screen events replace the software
  page's 250-millisecond readiness polling; page retries preserve working CarPlay resources.
- Use the same iAP2-capability chooser in both settings entrances and refresh from SDP/bond
  events. Reject a saved incompatible device class before network preparation; incomplete
  cached service data still gets SDP/protocol validation for renamed iPhones.
- Shorten failed Bonjour probe backoff and aggregate terminal endpoint failures per request
  across address families before early fast-reconnect fallback. Preserve finite re-browse,
  endpoint budgets and stale-request guards.
- Software regressions cover these changes on Android 9 paths. Vehicle automatic entry and
  actual startup latency require acceptance on the target head unit.

Autunn Play 0.2.71-AUTUNN14 (145, test release):

- Distinguish an automatic Host launch being received from the Host actually resuming with
  window focus. Keep the three-second foreground deadline after receipt; retry an accepted
  but hidden Host within the existing one/three-second backoff and three-submission budget.
- Preserve the original deadline and backoff across readiness events and service replacement.
  A late Intent cannot discard a queued retry; late foreground confirmation cancels it without
  requiring another resume. Release only the matching hidden Host's provisional UI ownership.
- Once resumed and focused, wait for a rendered current primary Surface without repeated
  foreground launches. Keep the current controller/media and preserve cancellation, settings,
  parking, manual stop, stale-stream and resource-cleanup barriers.
- Regression tests reproduce two lost-retry orderings on 144. They establish source defects;
  actual vehicle automatic entry and startup latency still require device acceptance.

Autunn Play 0.2.70-AUTUNN13 (144, test release):

- Recover Android 9 Wi-Fi Direct BUSY within the existing startup deadline, classify
  recoverable failures separately from configuration errors, and reinitialize a lost P2P
  control channel once while validating the existing group's ownership and interface.
- Try bounded Android 9 Auto channel candidates only after explicit creation rejection.
  Clear channel restrictions before system Auto; an unanswered or accepted creation does
  not authorize another create. Keep unavailable actual channel/frequency values unknown.
- After the first 30-second TCP timeout, resample a healthy owned GO and reserve one usable
  alternate address family across ordinary retries and same-boot process recovery. Bind
  the listener before publication; do not wait for an absent alternate address.
- Refresh changed Bonjour endpoints and resume bounded browsing after final probe failure.
  Preserve per-endpoint attempt limits, selected-phone identity, dual-stack independence
  and cancellation; stop discovery after authentication and a rendered primary frame.
- Correlate service startup, preparation gates, transport stages and visible entry using
  a common connection cycle and monotonic timestamps. Vehicle latency remains unmeasured;
  compare comparable cold starts and reconnections before accepting the speed improvement.

Autunn Play 0.2.69-AUTUNN12 (143, test release):

- Keep the installed launcher/OEM component as a no-display router in a separate transient task.
  A ready current session opens its CarPlay task directly, without creating the software page
  or restoring its task snapshot. Explicit setup/settings/recovery pages remain accessible.
- Re-read the selected phone's Bluetooth state every two seconds while monitoring, including
  delayed adapter availability and missed OEM broadcasts. Never scan, pair or open a page from
  these reads. Preserve positive observations across a transient negative snapshot; confirm a
  stale connection using two negative device reads. Close or target changes invalidate old work.
- Track automatic entry submission, Host acceptance and visible completion separately. Retry
  rejected or unaccepted submissions with bounded backoff, and recheck after foreground owners
  release the page. Cancellation, parking, manual stop and cleanup still invalidate queued entry.
- These regressions are verified in software tests; the reported 142 boot failure has no matching
  device log. Cold boot entry and absence of a visual flash still require vehicle acceptance.

Autunn Play 0.2.68-AUTUNN11 (142, test release):

- Keep the vehicle launcher visible during automatic startup when setup is missing, the selected
  phone is absent, automatic connection is disabled, or foreground service startup is rejected.
  Only a ready current primary stream can request automatic projection entry. Manual launcher
  and notification entry keep the setup and connection page available.
- Observe the selected phone's existing Bluetooth connection at service startup and subsequent
  connection, pairing and adapter events. Suspend new automatic work when the selected phone is
  known unavailable, without consuming connection retries; resume on its arrival. Preserve an
  existing healthy wireless session when Bluetooth is released. Unknown connection state keeps
  the existing compatibility path; an explicit Connect action can still initiate Bluetooth.
- Preserve manual stop, parking, cleanup, automatic-entry cancellation and hotspot repair gates.
  Persist whether a stopped retry requires user action so a Bluetooth event cannot bypass it.
- Opening the application before the primary stream is ready shows connection status while
  connection continues in the service; opening an already ready session restores CarPlay directly.
- Give a foreground dropped projection a five-second recovery interval before returning to the
  connection page. Transient recovery stays in CarPlay; persistent loss opens the software page
  only while the same foreground entry remains requested. This does not identify an iPhone Wi-Fi
  toggle or prove cold-connection latency, instrument-map rendering, or lane-arrow behavior.

Autunn Play 0.2.67-AUTUNN10 (141, test release):

- With boot startup and automatic connection enabled, prepare the connection in the foreground
  service while leaving the vehicle launcher visible. Open the existing CarPlay host once the
  current primary video stream is accepted, without waiting for an Activity or rendered frame.
- Keep background entry event driven and submit once per primary stream. Validate queued entry
  against the current request and stream; repeated service starts cannot undo a user's return
  to the vehicle UI. Only a focused Host with a rendered current Surface completes entry.
- Show the software page for missing saved setup, phone selection, required permissions or a
  system Wi-Fi action. Keep notification entry available if automatic display is rejected.
  Preserve manual disconnect, vehicle state, cleanup barriers and asynchronous licensing.

- Restore an already connected CarPlay host directly from the vehicle launcher before drawing
  the connection home page, including a reused singleTop launcher activity. Reuse the current
  controller and media; preserve explicit settings pages, stop/cleanup boundaries and fallback
  to the home page if the host launch is rejected. This is separate from cold connection speed.

Autunn Play 0.2.66-AUTUNN9 (140, test release):

- Restore the CarPlay accessory icon's vehicle launcher action (Android MAIN/HOME), retaining
  the current connection and cancellation of automatic projection entry. It no longer opens
  the Autunn Play home page. Current-session and closed-controller checks remain authoritative.
- The post-Bluetooth connection path is under review. This release does not claim a connection
  speed improvement; controller timing excludes earlier home/recovery/retirement preparation.

Autunn Play 0.2.65-AUTUNN8 (139, test release):

- Start the vehicle observation path when an automatic home-page request restores a parked use
  after a same-boot process replacement or update. Keep parking, manual stop, termination and
  disabled automatic connection authoritative; only fresh eligible vehicle observations resume.
- Reconcile a main frame rendered before RECORD with that same session's fast recovery proof.
  Install the recovery deadline before queuing preparation, and preserve current runtime facts
  that arrive through the retained listener while preparation is queued.
- Coordinate recovery request publication and completion through short ownership commits, with
  platform and discovery I/O outside the boundary. Recheck the adopted session when beginning
  recovery, and bind a matching current attempt after RECORD adoption if it began in between.
- Add regressions that fail on the previous implementation. These reproduce code ordering
  defects; without the matching vehicle log they do not establish the cause of the reported
  one-to-two-minute wait or disconnect tens of seconds after entry. Actual vehicle timing,
  instrument white pixels and lane-arrow output remain separate acceptance work.
- Retain the release signing, activation and pairing data, authentication checks, update
  authorization, Autunn request marker and resource-release barriers.

Autunn Play 0.2.64-AUTUNN7 (138, test release):

- Recover a previously authenticated phone on a still-owned, healthy Wi-Fi Direct network before
  rebuilding the Bluetooth bootstrap. Revalidate discovery identity, interface and pair-verify;
  require new runtime authentication and a main-screen frame. Stage deadlines and a total budget
  bound the attempt, with one Bluetooth fallback. Full power-off still releases the network.
- Defer new recovery work during a fresh OFF candidate without stopping current media on a
  single sample. Cancel the candidate on ON, unknown or expired sampling. Enable door return
  only for the verified HS5 firmware; a door event cannot override an explicit manual disconnect.
- Track main video, authenticated runtime, navigation messages and alternate-display content
  separately. Reject old sessions and channels, and merge same-revision display facts monotonically.
- Retry a written alternate-display request at most twice while waiting for its first valid video;
  report no-video after a finite deadline. Bind checks to the content revision, ignore unsealed
  short video records and preserve event socket, cipher and response ownership during replacement.
- Bound navigation detail storage by the advertised capacity and current route/list ownership.
  This does not change private lane subscription bytes or establish that lane arrows are restored.
- Make the CarPlay application icon open the explicit Autunn Play home page while retaining the
  session. Add physical Back delivery diagnostics; real hardware Back behavior remains unverified.
- Expire pending display-command responses even when no further messages arrive. Keep command
  writes, responses and visible effects distinct, with all timer work isolated from media teardown.
- Give client HTTP operations a monotonic total deadline and lifecycle cancellation, protect
  submitted activation claims, and reclaim only unowned update partial files. Preserve activation,
  pairing, release signing, update authorization, the Autunn marker and installation verification.
- Add regression coverage for the affected ownership, cancellation and lifecycle boundaries.
  Real HS5 connection timing, first instrument-map output, door return and audio remain acceptance
  steps; local tests and packaging checks do not replace those observations.

Autunn Play 0.2.63-AUTUNN6 (137):

- Treat repeated foreground submissions of the same startup configuration as unchanged. A Host
  readiness notification no longer invalidates an in-flight background construction and creates
  an endless prepare/retire loop. Real configuration, identity and intent changes still invalidate
  old work; stop completion continues to wait for resource release and durable intent.
- Explicitly request the alternate display's retained page and a key frame after SETUP and the
  event channel are ready. An initialURL offer alone no longer counts as delivered content.
  Instrument-map reopen and new-route events can request that page again; stale stream and
  controller callbacks cannot change the current one. Command writes are not pixel confirmation.
- Add bounded, privacy-preserving lane-request and lane-state diagnostics. Do not change the
  negotiated display mode, fabricate lane details, or claim missing instrument lane arrows fixed.
- Refresh interface mDNS discovery for phones that join after JmDNS's initial three-query window.
  Reuse the existing address-family worker and socket, back off from 1 second plus jitter to
  60 seconds, and stop refresh after a valid endpoint or close. The exact connection-time benefit
  remains a real-device measurement; this does not bypass authentication or change Wi-Fi groups.
- Retain existing activation, pairing, release signing and runtime authentication configuration.
  Local regression is separate from the pending real-HS5 acceptance of this version.

Autunn Play 0.2.62-AUTUNN5 (136):

- Advertise the receiver's base iAP control-channel capability when the event channel is available,
  including when a sender proposes only display features. Keep optional display/video feature
  negotiation and the existing runtime authentication and session ownership checks.
- Register runtime navigation only after identification, MFi authentication and subscriptions;
  bind prefetched navigation to that runtime's own AirPlay session and reject stale sources.
- Bound incomplete control/event RTSP and iAP messages to a 60-second total receive lifetime.
  Continuous fragments do not extend the budget; complete messages and normal idle connections
  have no receive timer. Keep arrival ages across authenticated frame decoding.
- Include the local 2026-10-18 connection, audio, licensing, parsing and diagnostic improvements.
  This version identifies the combined build; local regression and packaging verification do not
  substitute for HS5 connection, HUD, instrument-map, lane or audio acceptance testing.

Unreleased connection and navigation handoff fixes:

- Replace full control/event packet hex dumps with byte counts before AirPlay writes. Pairing,
  authentication, encryption and wire payloads keep their existing behavior.
- Allow the existing Wi-Fi scan pause on Android 9 as well as Android 10. Resolve the transaction
  from the device framework; require already approved local ADB and retain durable scan recovery.
  Same-LAN mode continues to leave station scanning enabled.
- Include the live Wi-Fi Direct GO interface's AP address in the Wi-Fi configuration hint when it
  can be read or unambiguously recovered from that interface's EUI-64 address. Never use a peer's
  P2P device address as the AP hint. Refresh the hint when reusing an owned live group. Frequency
  still comes from the live group/driver, with the existing unknown-channel fallback.
- Keep Bluetooth bootstrap free of the route component, route message declarations and route
  subscription. The Wi-Fi runtime link retains configured route/lane capabilities and navigation
  consumption. Wired subscriptions also follow their declared route capability.

Real HS5 validation remains required for scan permissions/recovery, connection time and reception
of lane details. These changes alone do not prove that the instrument lane overlay is restored.

Autunn Play 0.2.74-AUTUNN17 (148, test release):

- Resume pending background preparation on Wi-Fi or Wi-Fi Direct readiness, even before a
  Controller exists. Recheck current permissions, connection intent and cleanup gates;
  unregister the readiness receiver when the stack takes ownership or the request stops.
- Start a fresh 120-second wireless connection budget when the prepared stack takes ownership.
  Waiting for Wi-Fi user action does not spend this fresh budget. An existing attempt keeps
  its original absolute deadline across replacement and recovery.
- Keep the instrument map attached to route/session state independently of maneuver hints.
  Missing details, rerouting empty lists and stale hints clear HUD/TBT information without
  exiting and reentering the map or requesting its stream again. Explicit route/session end
  and the user's map visibility choice still release the map.
