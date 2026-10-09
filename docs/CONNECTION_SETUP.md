# FAW Play — Hongqi HS5 connection setup

This Android 9 HS5 edition uses Wi-Fi Direct for every connection. Existing
installs migrate saved car-hotspot, same-LAN and USB choices to Wi-Fi Direct.
Channel selection is automatic. The connection page no longer offers other
transports, hotspot credentials or manual channel controls.

On the Android 9 head unit, opening FAW Play requests Wi-Fi on when it is off,
including before an iPhone has been paired. An already enabled or enabling radio
is left alone. The connection worker waits for the radio to report enabled before
initialising Wi-Fi Direct, within the existing startup deadline and at most ten
seconds. Cancelling startup wakes this wait without creating a group. Refused
requests open the system Wi-Fi settings; Android 10 and later also use the system
Wi-Fi panel because ordinary apps cannot change that switch directly. The app
does not toggle tethering, fall back to a hotspot, or disable Wi-Fi on disconnect.

Keep Wi-Fi and Bluetooth enabled on the iPhone. Pair the
iPhone with the factory Bluetooth settings, choose it in FAW Play, and allow
connection permissions. Once paired, opening FAW Play reconnects automatically.
Automatic connection is a fixed behavior, with no separate switch.
Android 17 and later also require Local network access for AirPlay TCP and mDNS.
The app requests that runtime permission before starting the wireless transport;
Android 9 keeps its existing location-based permission requirements.

Open after the car starts defaults to enabled. A previously saved choice to
turn it off is retained. The head unit may require its own auto-start permission.

Home, connection and settings pages use immersive fullscreen, including when
returning from system settings or the CarPlay viewport. Android multi-window
mode retains system navigation. Home uses a bordered feature panel and a live
connection panel. "Open CarPlay" brings the existing projection to the foreground
without stopping a running or connecting session. Without a session it starts
the saved iPhone connection and the host displays CarPlay when video arrives;
without a saved phone it opens pairing in Connection settings.
Landscape settings use a fixed category sidebar and parallel cards, with every
category and its controls visible on one screen and no vertical scrolling.
The settings screen supports landscape only.
The layout scales its text, controls, icons and spacing against a 360 dp
landscape reference height. On the HS5's 1920 x 720 / 160 dpi screen this
produces a 2x UI scale while keeping every category on one screen. The app
does not change Android's display density or the CarPlay video scale.
Up to two paired phones share one horizontal row; further paired devices remain
in the horizontal chooser, keeping the connection actions visible.
It groups controls into Connection, Display, Navigation and HUD, Permissions
and Help, Diagnostics, and About. Pairing, phone selection, connection, and the
boot-start switch share one Connection category. The page header uses the official
Hongqi logo original image without cropping or matting; the Android launcher icon
remains unchanged. Resetting a connection requires
an inline confirmation; navigating away or cancelling does not reset it.

Steering-wheel configuration and advanced audio-routing controls are removed
from the UI. The HS5 previous/next, phone and voice integration and the validated
AUDIO7 microphone and playback behavior remain. Existing pairing, audio,
boot and gesture preferences are retained. HUD and location controls are in
Navigation and HUD. Instrument-map visibility is controlled by the navigation
gesture, with no duplicate settings switch.

The HS5 phone key keeps its short-press behavior: answer an incoming call,
end an active call, or open the phone page while idle. A long press sends the
explicit telephone Drop command to reject an incoming call or end a call,
without requesting the phone page. Qisi reports completed short presses as
action 0, long presses as action 1, and long-press completion as action 2;
these are not Android key-down/key-up events. Only one command is sent per
long press. PHONE22 still requires physical long-press validation on the HS5.

The CarPlay settings overlay and two-finger settings gesture are removed. All
remaining controls are in the regular settings page, with no settings-finger
selector. Display keeps only a 30/50/60 fps segmented control. Fixed runtime
parameters are ambient-light day/night mode at 30 lux with zero switching delay,
medium CarPlay size (300 mm), 100% resolution/UI scale, neutral picture settings,
H.264 hardware decoding, left-hand drive, automatic full-screen safe area, and
300 ms music buffering. Old saved values cannot override these fixed parameters.
While navigation guidance is active, a
three-finger swipe left opens instrument projection and a swipe right closes it. Outside navigation, the
app does not intercept that gesture. Ending navigation cancels an unfinished
gesture and clears HUD guidance and the factory instrument projection. An
already accepted gesture is drained until lift so it cannot leave stale touches.

If reconnect fails, use the confirmed CarPlay Wi-Fi reset action, then choose
the saved iPhone again. Export diagnostics with the failure time when needed;
do not share phone numbers or network passwords.

Pending HS5 validation: when the owner next connects the car through ADB,
first turn off the head unit Wi-Fi and verify both a cold launch and reopening
FAW Play enable Wi-Fi and reconnect the saved iPhone through Wi-Fi Direct. Also
check an already enabled radio, first-run pairing, and cancellation while enabling.
The API 31 emulator cannot verify Android 9 or this OEM radio implementation.
Then
verify FAW Play media, guidance and calls on BUS00_MEDIA, BUS01_NAVIGATION
and BUS03_CALL, including microphone clarity, independent volume context,
and media/navigation recovery after a call. Keep this vehicle check deferred
during the UI-only emulator preview; CarLink logs do not verify FAW Play calls.

HS5 gesture change in NAVI20: use three-finger left during active navigation to
open the instrument map, and three-finger right to close it. Despite its threeFingerGlide log name, Qisi's independent
input monitor accepts two to four fingers moving down and can inject Home;
consuming touches in FAW Play does not veto it.
The NAVI18/19 task-restoration workaround was removed. The app requires exactly
three fingers moving horizontally in the same direction. The steering-wheel Back key still closes
the instrument map without ending centre-screen CarPlay or HUD guidance.
Vehicle validation must confirm that horizontal swipes open and close the map
without a factory-desktop flash, including after launch from the factory app list.
