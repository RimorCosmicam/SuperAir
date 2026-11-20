# What was measured, and what is still a guess

The family's habit, continued: every claim here was either run on the phone and written down, or
is labelled as a guess with the experiment attached. miniMont's `docs/MEASUREMENTS.md` is the
model.

Phone: Galaxy Z Flip 7 (SM-F766B), **Android 17**, One UI 8.5, 20 November 2026. This is a release
later than any of the three parent projects was measured on, so the first four entries are
re-measurements of things they had already established.

## 1. A trusted virtual display, from the shell uid — ANSWERED, still open

> `ADD_TRUSTED_DISPLAY` is shell-uid-only, was removed in Android 15 QPR2, and was restored in 16
> with a note saying it may go again. miniMont called this "a door somebody else is holding open".

**It is still open on Android 17.** A display created from a shell process came back with
`FLAG_TRUSTED`, `FLAG_PRESENTATION`, `FLAG_OWN_DISPLAY_GROUP`, `FLAG_OWN_FOCUS` and the rest, and
an activity launched onto it.

This stays a standing watch rather than a closed question. Every One UI update, check it, and keep
the failure legible: the app should be able to say *this Android release closed the door* rather
than showing a black screen.

## 2. Landing an app on the display you meant — ANSWERED

`am start --display` only creates a task when the app has none. An app that is already running
comes forward **wherever it already is**, which on a folded phone is the cover screen under your
thumb.

Reproduced on 17 within minutes of starting: launching Settings onto a new display brought the
existing Settings task forward on the cover display instead, in freeform, where it sat as a
half-size window that could not be dismissed.

Kamui Desktop's answer is the one SuperAir uses and it is still correct: look for an existing task
first, move it with `IActivityTaskManager.moveRootTaskToDisplay`, then launch, then check again —
and if it ended up somewhere else and will not move, fail loudly rather than leaving it there.

## 3. Handing a binder from a shell process to an app — ANSWERED

The new question, and the one SuperAir rests on, because a `Surface` cannot travel any other way.

`IActivityManager.getContentProviderExternal(authority, 0, token, tag)` works from uid 2000 and
starts the app if it is not running. Two things had to be got right:

- **The provider must be exported.** `getContentProviderExternal` still refuses a provider the app
  has not exported — "Permission Denial: opening provider ... that is not exported from UID
  10037". Shizuku exports its provider too; the door is narrowed by checking the calling uid
  inside `call` instead.
- **There are two `call` overloads and only one carries an authority.** The legacy
  `call(String callingPkg, String method, String arg, Bundle)` is still present on this device, and
  reflection listed it first. Taking it shifted every argument by one and the framework refused the
  lot with "The authority unknown does not match the one of the contentProvider". The overload is
  now chosen by shape — the one whose first parameter is an `AttributionSource` — never by order.

With both fixed the app receives the binder and calls straight back: 92 launchable apps came over
the wire in the first round trip.

## 4. Telling the glasses from everything else — ANSWERED

miniMont and TheHud both pick the glasses by display id, taking anything past the phone's own two
screens. That is not enough on a phone that has other projects on it: **a FarGalaxy virtual
display sitting at id 7 was taken for the glasses** on the first run, and SuperAir reported itself
ready on a screen that does not exist.

SuperAir now requires `Display.getType() == TYPE_EXTERNAL`. The glasses arrive over DisplayPort, so
they are the one external display; everything virtual is somebody's software, SuperAir's own panels
included.

The reverse is not fixable from here. **TheHud claims any display past the phone's own two**, so
with both installed it will take the glasses — and it took SuperAir's stand-in display, which is
how this was noticed. Stop one before running the other.

## 5. The cover screen's full height — ANSWERED, and it is not ours to fix

`com.samsung.android.coverscreen.subscreen_compatible` in the manifest is necessary and **not
sufficient**. With it set, One UI still letterboxed SuperAir into the top ~840 of the cover
display's 1048 and put its own expand chrome underneath.

The allow-list is Good Lock's: `secure/multistar_cover_widget_backup_list`, which on this phone
already contains `com.minimont`, `com.minidex.app.debug` and `com.minimate`. It is paired with
`multistar_cover_widget_policy_list`, which holds hashes — so writing the backup list by hand is
unlikely to take and was not attempted. **The user adds SuperAir in Good Lock → MultiStar.**

## 6. Sizing another app's task at drag speed — NOT ANSWERED, AND NOT NEEDED

miniMont left this open and expected the answer to be no: a process spawn per motion event is not
a slow drag, it is not a drag.

SuperAir never asks the question. A panel's size is a number of grid cells, so a resize happens
once, on release, and is `VirtualDisplay.resize` rather than a task bounds change. Carrying a panel
moves it between cells, which is a layout change in our own scene and never touches the task at
all.

If free positioning is ever wanted, the question comes back exactly as miniMont wrote it.

## 7. What a panel costs — NOT MEASURED

Unknown and worth knowing before many panels are open at once:

- Whether several `SurfaceView`s on one Presentation stay on hardware overlay planes or fall back
  to GPU composition, and at what count.
- What a panel costs in power with its app idle, given the host holds a screen wake lock because
  virtual displays otherwise follow the phone into doze at 30fps.
- Whether `VirtualDisplay.resize` under a live `SurfaceView` ever drops the surface in a way that
  lets the framework relocate the task — the failure every project in this family has hit once.

## 8. The IME on a panel display — NOT MEASURED, AND ROUTED AROUND

Every panel display is set to `DISPLAY_IME_POLICY_HIDE`, because a text field on the glasses must
never pop a keyboard over the trackpad your thumb is on. Typing goes the other way: the cover
keyboard sends whole strings, and `KeyCharacterMap` turns them into the events a physical keyboard
would have produced.

What is not known is whether an IME could attach to such a display at all. It has not needed to.

## 9. The glasses themselves — PARTLY ANSWERED

Sections 1 to 8 were measured against the stand-in display, before glasses were plugged in. Since
then SuperAir has been used with Xreal glasses. They come up as one `EXTERNAL` display named "Air"
at 1920x1080, which section 4's rule picks out, and the scene and its panels draw there.

Still to find out:

1. Whether black is transparent enough in practice, or whether panels need their own contrast
   against a bright room.
2. Whether 160dpi is the right density for a panel at arm's length. It was chosen by reasoning,
   not by looking.

## 10. Where One UI puts a new task — ANSWERED

A new app opened onto a panel flashed up on the cover screen first and was moved after. One UI's
`ActivityStarter` logs "modifying PreferredTaskDisplayArea for launching app": for any package
enabled for the Cover Launcher, while the cover has focus, a new task goes to the cover whatever
display was asked for.

The host now gives focus to the glasses for the launch and hands it back afterwards
(`Panel.launch`). It has to be a real task: `focusTopTask` on the glasses display picked DeX's
empty "Desk" container and did nothing, so `setFocusedTask` is called on a task found there.

## 11. Display groups and the power key — ANSWERED

`OWN_DISPLAY_GROUP` is honoured on paper and ignored in practice: every panel lands in display
group 0 with the phone, so the power key sleeps them too. `DEVICE_DISPLAY_GROUP` changes nothing
and was taken out.

`isInteractive()` stays true while the cover sleeps, because the glasses keep the group awake.
`isDisplayInteractive(1)` is the one that tells the truth about the cover.

So the host owns the power key. It reads `KEY_POWER` from the input device with `getevent`, which
the shell user may do. After a press it:

1. waits for the cover to go non-interactive;
2. wakes the phone and locks it;
3. switches the cover's panel off with `setDisplayPowerMode`, through `DisplayControl` loaded from
   `services.jar`.

The next press powers the cover back on and brings SuperAir to its front. Polling missed
fingerprint-speed sleeps and wakes; the key events do not. A shutdown hook, and a check when the
host starts, make sure a host that dies never leaves the cover dark.

While the cover is dark the app drops every touch. Without that, a phone in a pocket unlocks
itself.
