# Battery for Windows — tray icon, flyout panel, and the WSL problem

The fourth surface, after the macOS menu bar, the iPhone, and Android. Same
OAuth client, same `/api/oauth/usage` endpoint, same terracotta.

The headline difference from macOS is one line:

```
the files Battery reads are not on this machine
```

On a Mac, Claude Code's credentials, `stats-cache.json`, `projects/` and
`history.jsonl` sit in the same filesystem as the app that reads them. On
Windows a large share of Claude Code users — the author included — run Claude
Code **inside WSL**, so all of it lives across a 9P network redirector at
`\\wsl.localhost\<distro>\home\<user>\.claude`.

Everything below follows from that one fact.

| | |
|:--|:--|
| ![The panel, dark](../assets/windows-panel.png) | ![The panel, light](../assets/windows-panel-light.png) |

Rendered by `--screenshot`, which draws the panel through the same Skia path a
real window uses — so these are the actual rendering, not a mockup, and no window
is opened to produce them.

---

## Reading the file boots the machine

Accessing a `\\wsl.localhost` path **starts the distro if it is not running.**

A tray app that reads a credential every sixty seconds would therefore boot a
Linux VM and hold roughly a gigabyte of RAM, on a machine where the user may not
have opened a terminal all day. That is disqualifying behaviour for something
that lives in the notification area, and it is not the kind of bug that shows up
in a screenshot — the app would look perfect while doing it.

Three rules keep it from happening, and each one already had a home in `core`:

**Probe, never assume.** `wsl.exe -l --running -q` lists running distros without
starting any. Nothing touches a WSL path unless that probe named the distro
first. Its output is **UTF-16LE with CRLF** — verified by hexdump rather than
assumed — so it must be decoded explicitly. Read with the JVM's default charset
it becomes a NUL-interleaved string that compares equal to nothing.

**Do not re-read credentials on the poll.** The access token is cached in
memory; the file is re-read only as expiry approaches. `StoredTokens.isExpiringSoon`
and `AppConfig.TOKEN_REFRESH_LEEWAY_SECONDS` already model exactly this, so the
credential file is touched every few hours rather than every minute.

**Gate local history on the panel.** Streaks, the heat map, the seven-day chart
and the project breakdown are read when the panel opens, not on a timer. Nobody
needs a heat map recomputed behind a closed window.

### The probe is side-effect free. Demonstrated, not assumed.

The first of those three rules is the one the other two rest on, and until now it
had never actually been tested. Ubuntu was running throughout Phase 1, so the
probe had only ever been asked about a distro that was already up — which cannot
distinguish *does not start one* from *did not need to*. Settling it needs a
second distro that is definitely down, so one was installed and never launched:

    wsl --install -d Debian --no-launch

| | |
|:--|:--|
| `wsl -l --running -q`, five times | Debian stays **Stopped**, and is correctly absent from the output |
| Reading `\\wsl.localhost\Debian\etc\os-release` | Debian goes **Running** |
| `wsl --terminate Debian`, then three more probes | Debian stays **Stopped** |

The middle row is the control, and it is what makes the other two mean anything:
the same machine, seconds apart, does start the distro the moment something
touches its filesystem. The probe is therefore quiet by demonstration rather than
by absence of evidence, and it is quiet both for a distro that has never run and
for one that has been terminated.

The control also sharpens the rule above it. That path read **failed** — a distro
with no user account yet answers 9P with an I/O error — and the distro booted
anyway. It is naming the path that starts the VM, not reading it successfully, so
there is no such thing as a cheap failed attempt.

## `.liveUnavailable` stops being an edge case

macOS treats an unreadable Claude Code credential as a rare accident: a denied
keychain prompt, a renamed directory. The fix in `32b7451` made the lookup
three-state so that a mapped account never falls through to Battery's own token
store — because falling through mints a second refresh chain, and single-use
refresh tokens mean one of the two copies is then stranded.

On Windows that "rare accident" is **a daily, routine, entirely correct state**:
the distro is simply shut down. The same three-state lookup ports unchanged and
carries far more weight here. Only the wording changes — not "credential
unreadable" but "Ubuntu isn't running", which is a fact about the user's machine
rather than an error about Battery's.

## Two installs, one tray

The same duality has a second consequence, and it only shows up once *both*
sides work. A machine with WSL routinely carries two Claude Code installs, the
same account signed in on each, each holding a live credential:

```
Windows: C:\Users\Ivan\.claude            token valid for 6h 59m
WSL: Ubuntu: \\wsl.localhost\Ubuntu\...   token valid for 5h 12m
```

[`DirSelection`] settles which directory to use when only one *can* answer. It
has nothing to say when both can, so the tie falls through to
`ClaudeConfigDir.candidates()`'s ordering — which is by **cost**, native first,
because that path needs no distro probe. The app therefore reads the native
install, silently, on the machine of someone who runs Claude Code in WSL.

That is not a cosmetic preference. The two report different five-hour windows —
8% with 4h 29m left on one, 14% with 3h 42m on the other — because they are
different sessions. Guessing is visibly wrong rather than merely arbitrary, and
no fact available to the app decides it. So it is a setting: a `Source` submenu
in the tray listing what `candidates()` found, ticked against the live one, and
`SourcePreference` remembering the answer across launches.

**The preference stores the origin, not just the path**, and that is the whole
point of the file format. A bare `\\wsl.localhost\...` path replayed on the next
launch would be a `DirOrigin.EXPLICIT` directory — and an explicit directory is
deliberately *not* distro-gated, because naming one is a claim that you can
already reach it. Pinning a distro that way would mean touching a
`\\wsl.localhost` path on every launch, which starts the distro. Restoring the
origin keeps `CredentialBridge`'s gate, so a pinned distro that is shut down
says "Ubuntu isn't running" for free.

A pin also outranks the candidate list when it is *absent* from it. Falling back
to the other install would answer with a different session window under a label
the user did not choose, which is worse than saying nothing.

### A second account is a second directory

The `Source` menu was built for one duality — native or WSL — and a work
account is a different one. Claude Code's own answer is `CLAUDE_CONFIG_DIR`: a
complete second install at `~/.claude-work`, in the same home, in the same
distro, with its own credential, its own `projects/` and its own `.claude.json`
*inside* it rather than beside it.

The WSL path was hardcoded to `.claude`, so that install was invisible. It is
now an enumeration of `.claude*` in each home, filtered by `looksValid` so a
stray `.claude-backup` is a name rather than an offer. The listing is one
directory read, and for a WSL home only for a distro `Wsl.running` has already
named — the same gate as everything else here.

Two of them in one distro both read "WSL: Ubuntu", so the part of the name that
is not `.claude` becomes a qualifier, and the menu carries the account rather
than the path:

```
Windows — ivanprtljaga.dev@gmail.com
WSL: Ubuntu — ivanprtljaga.dev@gmail.com
WSL: Ubuntu (work) — Advancedbytez
```

The organisation when there is one, because that is what identifies a work
install at a glance; the address otherwise. Reading it costs one small file per
candidate, and only when the running distros change.

An explicit `CLAUDE_CONFIG_DIR` in the Windows environment still names one
directory and means it — enumerating around it would be second-guessing
somebody who has already been specific.

## What Windows-native Claude Code does

Probed on a real install rather than assumed, the way `ClaudeConfigDir` pins
Claude Code's config layout:

| | |
|:--|:--|
| `%USERPROFILE%\.claude.json` | **Present, with a complete `oauthAccount` block** — `accountUuid`, `emailAddress`, `organizationName`. `ClaudeConfigDir.identity(fromConfig:)` parses this shape today and ports with no changes. |
| Location of that file | *Beside* `%USERPROFILE%\.claude\`, not inside it — the same special case macOS already carries for `~/.claude.json`. |
| Credential Manager | **No entry.** `cmdkey /list` names nothing matching `claude`. |
| `%USERPROFILE%\.claude\.credentials.json` | Absent on the probed machine, which had a stale native install. |

So the credential bridge is a **plaintext file read on both paths**, WSL and
native. There is no keychain, no `CredRead`, no permission prompt — this is the
one place Windows is simpler than macOS rather than harder.

The Windows `oauthAccount` block also carries fields no client currently reads —
`hasExtraUsageEnabled`, `userRateLimitTier`, `organizationRateLimitTier`,
`billingType`, `seatTier`. That is a free local signal for plan-tier detection
that the desktop app presently infers, and it would improve macOS too.

---

## Sharing `core`

`core` is **not copied and not moved.** `settings.gradle.kts` mounts
`../android/core` into this build by redirecting `projectDir`:

```kotlin
include(":core")
project(":core").projectDir = file("../android/core")
```

Two things that look like alternatives are not:

**`includeBuild("../android")`** — a composite build has to evaluate the
included build's settings, which applies AGP and demands an Android SDK. A
Windows machine that only wants the desktop app would fail for no benefit.

**Promoting `android/core` to a root `core/`** — structurally honest once two
platforms share it, and wrong for this fork today. Android work here is
upstreamed to `allthingsclaude/battery`, where a root `core/` does not exist;
moving it would make the Android tree diverge and every future Android PR
harder. Worth proposing upstream as its own change once there is a shipped
Windows app to justify it. Until then the cost of borrowing is one comment.

This works only because `../android/core/build.gradle.kts` is honestly
Android-free — `kotlin-jvm`, one runtime JSON dependency, nothing else. **That
file is the contract.** Its own comment already names the line to defend: *"the
moment this module needs `android.*`, the shared fixture story is over."* This
build is what that discipline bought.

Two consequences worth knowing:

- The version catalog here **must** define `kotlin-jvm` and
  `kotlinx-serialization-json` under the same aliases as `android/`, because this
  build resolves `../android/core/build.gradle.kts` against *this* catalog.
  Drifting the Kotlin version would compile the same source two different ways.
- `core`'s test task resolves fixtures as `rootProject.projectDir.parentFile/fixtures`.
  From `android/` that is the repo root; from `windows/` it is *also* the repo
  root. The golden fixtures work from both builds by construction.
- Both builds write to `android/core/build`. Running them simultaneously is not
  advisable; nothing else about it matters.

## Layout

```
windows/
  app/     everything Windows: tray, flyout, WSL resolver, poll loop
  core/    → ../android/core, mounted in place, never edited from here
```

## What a real Windows run settled

Phase 1 was built on a Linux JVM and first run on Windows afterwards. Everything
platform-specific worked unmodified, which is worth recording because none of it
could be tested where it was written:

```
WSL distros running: Ubuntu

Windows: C:\Users\Ivan\.claude
  identity: [redacted]
  token:    Claude Code isn't signed in here [FILE_MISSING]

WSL: Ubuntu: \\wsl.localhost\Ubuntu\home\ivan\.claude
  identity: [redacted]
  token:    valid for 6h 24m
```

`RealWslCommand` against a real `wsl.exe`, `%USERPROFILE%` resolution, the UNC
spelling, `whoami` resolving the Linux user through the UTF-8 path while the
distro list came back UTF-16LE, `.claude.json` found *beside* the directory on
both sides, and the credential read across 9P — all first time.

It also found a bug that only a machine with two directories could have found.
The native install had been signed in once: it keeps a `.claude.json`, so it
looks valid and reports an identity, but holds no credential. Both the poller and
the panel took the first candidate, and `candidates()` orders by *cost* — native
first, because it needs no distro probe. So the app blocked on an empty directory
while a token with six hours left sat one line below it. Cost is the right order
to offer directories in and the wrong one to choose from; [`DirSelection`] now
draws that distinction, and the case is a test.

**A tray-only first run is invisible.** Windows hides a new notification-area
icon behind the overflow chevron, and nothing may promote it out of there except
the user dragging it — so `gradlew :app:run` with no arguments looked exactly
like a hang, when in fact the app was running fine and Gradle was simply waiting
for it to exit. macOS has no equivalent problem, a menu bar item is just there.
So the app now shows its panel on the very first launch, and only then.

One cosmetic thing the same run exposed: the Windows console runs a legacy code
page, so every em dash in this app's output arrived as `?`. The `run` task now
sets `stdout.encoding`.

## Running it on Windows

Needs a JDK 17 or newer — Gradle itself needs a JVM to start, so this is the one
prerequisite that cannot be automated away:

```
winget install EclipseAdoptium.Temurin.21.JDK
```

Then, from `windows\`:

```
.\gradlew.bat :app:run --args="--panel"
```

`--panel` shows the flyout immediately. Without it the app starts as a tray icon
only, which on a first run looks like nothing happened.

The build resolves its own Skia binary from the host, so nothing needs
configuring: run it on Windows and it fetches `desktop-jvm-windows-x64`, the same
build file that fetches `linux-x64` here.

If it finds nothing, `--args="--headless"` prints the whole resolution chain —
which directories it considered, which distros are running, whose account each
one is signed in as, and exactly why a credential could not be read.

## Status

**Phase 0 — build wiring. Done and verified.** `:app` links against the borrowed
`core` with no Android SDK present, and `gradlew :app:run` prints the shared
level ramp and endpoints. `gradlew :core:test` then runs the shared suite —
**118 tests, 12 suites, 0 failures** — from *this* build against the golden
fixtures at the repo root, which is the borrowed-module wiring proving itself
end to end.

```
cd windows && ./gradlew :app:run :core:test
```

**Phase 1 — headless poller. Done; verified on a Linux JVM, not yet on Windows.**
`ClaudeConfigDir` (native path, running-distro enumeration, `looksValid`,
identity), `CredentialBridge` with the three-state lookup, `TokenCache`, and
`UsagePoller`. 28 tests. Run end to end against a live credential and the real
API:

```
gradlew :app:run                        # resolve, report, poll once
gradlew :app:run --args="--watch"       # keep polling
gradlew :app:run --args="--dir <path>"  # an explicit directory
```

The `--dir` override is the headless stand-in for the macOS folder picker, and
the only way to exercise any of this on a machine that is not Windows.

Two things it settled that were not obvious:

**`wsl.exe` speaks two encodings.** Its own listings are UTF-16LE; when it is
merely launching a Linux program, the bytes are that program's, and they are
UTF-8. `WslCommand` splits `run` from `exec` for exactly this reason — decoding
`whoami` as UTF-16LE yields a username matching nobody, silently.

**The poller must never call `UsageApi.fetchUsage`.** That method refreshes a
token close to expiry, and refreshing is the one thing a bridged client must not
do. `UsagePoller` calls `requestUsage` only; when a token goes stale the answer
is to re-read Claude Code's file, never to rotate its chain.
**Phase 2 — tray and flyout. Done, and now verified in a real notification
area.** Compose Multiplatform 1.12.0 against Kotlin 2.4.10, confirmed building
and running rather than assumed. 42 tests in `:app` at the time, alongside
`core`'s 118.

```
gradlew :app:run                                     # tray icon; click for the panel
gradlew :app:run --args="--panel"                    # panel up front
gradlew :app:run --args="--headless"                 # Phase 1's console poller
gradlew :app:run --args="--screenshot build/shots"   # every surface to PNG, no window
```

**The tray icon is drawn, because Windows has no text in the notification area.**
The macOS display modes ("percentage + time", "percentage only") are AppKit
laying out a string; here the same choice becomes three *pictures* —
`TrayIconRenderer.Mode` — each rendered per poll at whatever size the current DPI
asks for. Java2D rather than Compose: the shell wants a `BufferedImage` at an
exact pixel count, which is what Java2D is for and what a composable is not. Each
of 16/20/24/32 is drawn at its own size; rendering one and letting the shell
scale it is what makes a tray icon look muddy. Below 24 px the combined ring-plus-
number mode drops the number rather than draw a smudge, which is a test rather
than a hope.

**A stale reading is dimmed, never blanked.** When the distro stops or the
network drops, the last figure is still the best answer available, so the ring
goes hollow and the status dot goes grey while the numbers stay. Blanking the
panel for a routine, self-healing state would be the more misleading choice.

**No Material3.** The panel sets its own colour, size and weight on every call,
so Material would be a layer of defaults nothing reads; `BasicText` from
foundation is the whole requirement. Every colour comes from `core`'s
`BatteryPalette`, so nothing here holds a fourth opinion about what the brand is.

`--screenshot` renders the panel through the same Skia path a real window uses,
via `ImageComposeScene`, so a layout can be reviewed without Windows, a
credential, or a display. That is how both themes above were checked.

### What seeing it settled

Everything above was true of a rendering. Four things were not true of a window,
and none of them could have been found without one.

**`SystemTray.trayIconSize` is not a pixel count.** On a 150% display it answers
16 — the same as at 100% — because the figure is in AWT's scaled user space,
while the shell rasterises the icon at 24. So the app drew a 16 px bitmap and let
Windows stretch it, which is precisely the muddiness `TrayIconRenderer` draws per
size to avoid, and it is invisible in a PNG because the PNG is the crisp original.
It also held every scaled display below the threshold at which the combined mode
draws its number, so the icon silently lost the percentage on exactly the
machines with room for it. Multiplying by the screen's scale factor is the fix.
Compose is not at fault and behaves well: its `Tray` asks the painter for 16 dp
at the current density, so a bitmap of the right size arrives one-to-one.

**A percentage inside a ring needs 24 px, not 20.** Judged against a real
taskbar with both in front of each other. At 20 the two digits sit in about eight
pixels of ring interior and anti-alias into a smudge that reads as a dirty icon
rather than as a number — worse than the clean ring it replaces. At 24 they
resolve. The ring alone stays right at 16: the mode that carries a legible number
in a 16 px box is `PERCENT`, where the digits get the whole icon instead of the
hole in the middle of a gauge, and that is the answer for anyone who wants a
number at 100% scaling.

**Compose packs a size-`Unspecified` window exactly once**, while it is still
undisplayable — `if (!isDisplayable) { setPreferredSize(...); pack() }`, and never
again. So the first layout wins, and the first layout is the empty state: one
line of "waiting for the first reading". When the reading arrived the panel grew
three gauges taller and the window did not, which is the same clipping the fixed
height used to cause, arriving by a different route. The window has to be
re-packed by hand whenever the panel changes shape, and the frozen preferred size
cleared first or `pack()` simply re-applies it.

**The icon opened on a double click.** Compose's `onAction` is AWT's `TrayIcon`
ActionListener, and on Windows that fires on the second click. Every native
flyout in the notification area opens on the first one, so the icon looked dead
to anyone who clicked it the way Windows taught them — and a tray app whose icon
does nothing is indistinguishable from a tray app that has crashed. There is no
Compose hook, so the listener goes onto the AWT icon directly, reached through
`SystemTray.trayIcons`.

**The poll loop ignored `core`'s backoff.** `PollBackoff` has been in the shared
module since it was ported from `UsagePollingService.swift` — escalating 60s to
600s, honouring `Retry-After`, tested. Nothing on Windows asked it anything: the
loop delayed a flat sixty seconds whatever came back, so a 429 was answered
exactly sixty seconds later, for ever, which is not how you stop being rate
limited. The fix is that the loop sleeps for what `AppState` says rather than for
a constant. Worth recording as a failure mode rather than a typo — the port had
the answer in the module it borrows and reimplemented the cadence beside it.

**AWT menus have no radio item.** `MenuScope.RadioButtonItem` compiles and then
throws `java.awt.Menu doesn't support RadioButtonItem` when the menu is built,
because AWT's menus are made of `CheckboxMenuItem` and nothing else. The Source
group is a tick rather than a bullet, which is what the platform can draw. It is
also the one thing here no test could have caught: it fails inside the tray's
composition, at the moment a real menu opens.

**The rounded corner was four white wedges.** The panel rounded itself with a
`clip(RoundedCornerShape(12.dp))` inside an opaque square window, and a clip
paints a shape — it does not remove pixels from a window. So the four corners
outside the arc stayed the AWT frame's default light grey, which is invisible in
a PNG with a transparent background and glaring on a desktop.

The repair is not a transparent window. Windows 11 already has an opinion about
a flyout's corner, applies it at the compositor where the shadow and the hit
region come with it, and lets the user turn it off system-wide — so a hardcoded
12 dp is a fourth opinion about something the platform owns, of exactly the kind
the palette refuses to have. The panel now paints the window edge to edge and
`WindowCorner` asks DWM for the corner via `DWMWA_WINDOW_CORNER_PREFERENCE`,
which has to be asked for: Windows 11 rounds a framed window on its own and
leaves an undecorated one square, confirmed by reading the attribute back off a
live flyout and finding `DWMWCP_DEFAULT`.

The 12 dp survives in one place — `--screenshot`, whose output is an image with
nobody to round it.

### Where the flyout lands

`WindowPosition.Aligned(BottomEnd)` was always a placeholder, and Windows 11 is
the version that makes it wrong rather than merely crude: the taskbar is centred
by default, so the notification area is nowhere near the corner the flyout was
anchoring to. `Shell_NotifyIconGetRect` answers the question directly — it
returns the screen rectangle of one specific icon, and the overflow chevron's
rectangle when that icon is hidden behind it, which is the case a corner guess
has no way to handle at all.

The awkward part is naming *our* icon, because the call takes the `(hWnd, uID)`
pair the owner passed to `Shell_NotifyIcon` and `java.awt.TrayIcon` exposes
neither. Both are recovered from outside: AWT's owner is a top-level window of
class `SunAwtTrayIcon` in this process, and the `uID` is found by offering the
shell the first few candidates and keeping the one it recognises — it answers
`S_OK` for an icon it knows and an error for anything else, which makes it a
lookup rather than a guess. On this machine that is `uID = 1`, and the rectangle
it returned matched the icon's measured position on screen exactly.

The panel is then centred on that rectangle and clamped into the work area, above
the icon or below it depending on which side the work area is — which is what
makes a top or side taskbar work without a special case. The geometry is a pure
function of two rectangles and a size, so all of it is tested; only the call that
produces the first rectangle needs Windows.

**Phase 3 — local history. Done and verified against a live WSL install.**
A seven-day chart and a project breakdown, read from Claude Code's own
transcripts when the panel opens.

Three things a real machine settled, none of which the plan had right.

**Nothing can watch these files.** `ReadDirectoryChangesW` was expected to fail
over 9P. It fails *silently*, which is worse and changes the design rather than
confirming it:

```
ReadDirectoryChangesW armed: True     <- no error, no exception
events seen: 0                        <- write from inside the distro
events after a Windows-side write: 0  <- control: written from Windows
mtime changed:  True
size changed:   True (6 -> 49)
```

The control row is the one that matters. A change made from the *Windows* side
of the same share raises nothing either, so this is not "Linux-side writes do
not reach the notification channel" — the 9P redirector implements no change
notifications at all. And `EnableRaisingEvents = true` succeeds, so the obvious
design — try a watcher, fall back to polling if it fails — would never fall
back. Polling is not the fallback here. It is the mechanism.

**`stats-cache.json` does not exist.** Not on the WSL install, not on the native
one. `StatsCacheService.swift` treats it as the primary source and the JSONL as
a supplement; here the supplement is the whole story, which deletes a tier of
the macOS design rather than porting it. `history.jsonl` is 27 prompts with no
token counts — macOS calls it a "recovery source of last resort" and that is
exactly what it is worth.

**`cwd` is per message, and it wanders.** The obvious way to attribute tokens to
a project is the `cwd` on each line. One real session logged 224 messages at
`…/battery`, 63 at `…/battery/windows` and 3 at `…/battery/android` — one piece
of work, split three ways and ranked by where somebody happened to be standing.
The panel showed `battery`, `windows` and `android` as three projects, which is
how the bug was found: it looked wrong before it could be reasoned about.

The stable identity is the transcript's *folder*, which is Claude Code's own
notion of a project and fixed for the session. The folder name is only the path
with separators replaced by dashes, so it cannot be decoded without ambiguity
(`my-app` is indistinguishable from `my/app`) — so the shallowest `cwd` observed
in that folder is preferred as the name, since every wandered path is below the
session root. `StatsCacheService.displayName(forPath:)` ports as it stands for
the rest, plus five leaf names this repo earns: its own tree is `android/`,
`ios/`, `windows/`, and a row reading "windows" names nothing.

**What it costs.** Measured over 9P rather than guessed: 2.1 MB of transcript
reads in 68 ms, a stat pass over the tree takes 8. So the shape is stat
everything, discard anything older than the window, open only what survives —
and do all of it when the panel opens, never on the poll timer. A seven-day
chart recomputed behind a closed window costs a Mac some CPU and costs this app
a round trip into a virtual machine.

**One gate, one definition.** Local history is the second thing to read a
`\\wsl.localhost` path, and a second private copy of the rule whose failure mode
is "silently boots a virtual machine" is not a duplication worth having.
`Wsl.reachable` is now that rule, in one place; `CredentialBridge` asks it too.

**Hot sessions without a hook.** The plan was `battery-hook.sh` writing a marker
to `/mnt/c/...`. Inferring it from the newest transcript write needs no hook, no
edit to anybody's `settings.json`, and works on both installs at once. The
threshold is `core`'s `SessionPolicy.END_GRACE_SECONDS` rather than a fresh
number — the other platforms already argued about what "still working" means,
and thirty minutes of not burning tokens is a code review, not the end of a
session.

Deliberately not built: the streak and the heat map. Both are in the macOS
panel; neither answers a question this one is being opened to ask.

The section collapses, which is macOS's `showDetails` switch gating the same
content. It earns more here: with it closed the read does not happen at all, and
the read is megabytes across a 9P redirector into a virtual machine.

---

**Phase 4 — toasts and the taskbar. Done, and it is smaller than the plan.**
93 tests in `:app`. Both halves of this phase changed shape when a real machine
was asked about them, and in both cases the answer was less code rather than
more.

**WinRT was not necessary.** The plan said toasts via JNA:
`ToastNotificationManager`, `RoGetActivationFactory`, HSTRING marshalling, an
`IInspectable` vtable walked by hand. `TrayIcon.displayMessage` produces a
genuine Windows 11 toast — in the notification centre, with the system's own
styling and dismiss affordances — because Windows routes `Shell_NotifyIcon`'s
`NIF_INFO` into the modern notification system and AWT already speaks it. The
delivery mechanism is one call and no native code at all.

What *did* need a native call was the name on it. The first toast was headed
**"OpenJDK Platform binary"** with a coffee-cup icon, because a notification is
attributed to its process's AppUserModelID and an unregistered `java.exe` has
the JDK's. `SetCurrentProcessExplicitAppUserModelID` fixes that, and its limit is
worth writing down because it is a packaging fact rather than a notification one:
with no Start Menu shortcut carrying the same ID, Windows has no display name or
icon to resolve and shows the raw string. Measured, not assumed — the toast now
reads `com.allthingsclaude.battery`. jpackage's `--win-menu` writes exactly that
shortcut, so the friendly name and the terracotta icon arrive with Phase 5 and
not with more Win32.

**`ITaskbarList3` had the premise backwards.** The plan was an overlay badge on
the app's taskbar button. Looking at a real taskbar found the button already
there, wearing the Java Duke icon — and it should not exist. The flyout is a
popup that appears when the tray icon is clicked and vanishes when you click
away, so the button flickers in and out beside a tray icon that is already this
app's presence on the taskbar. There is nothing to badge, and badging a button
that should not be there is not an improvement. `WS_EX_TOOLWINDOW` removes it,
and takes the flyout out of Alt-Tab too, which is the same correct answer to the
same question. Compose's undecorated window has neither that flag nor an owner
(`exStyle 0x00000008`), which is why it got one.

**What is left is the decision, and that is the part worth having.**
`ThresholdAlerts` ports `NotificationService.checkThresholds` and its
neighbours — 80/90/95, said once, re-armed on the way down, debounced an hour so
a figure hovering on a boundary is not an event — with this codebase's usual
change to ported logic: it is pure, so all of it is tested. The macOS original
is private state on a service that calls `UNUserNotificationCenter` inline, so
none of its arithmetic is covered there. Interrupting someone twice for the same
thing is the sort of bug nobody reports; they just turn notifications off.

One deliberate departure. macOS fires every threshold crossed in a poll, so 78%
to 91% produces two toasts stacked up describing the same moment. Here the lower
ones are recorded as said without being said: the highest carries strictly more
information, and one interruption is the quieter reading of "tell them when it
matters".

A tray tick turns the whole thing off. One switch rather than the three macOS
offers, because the question people have is "stop talking to me" — and because
the usual Windows answer, Settings › Notifications, does not list an unpackaged
app whose AppUserModelID resolves to no Start Menu entry. Until Phase 5 registers
one, this switch is the only one there is.

Whether a toast actually reaches the notification centre is not a thing a test
can answer, which is the same gap `--screenshot` fills for the panel:

```
gradlew :app:run --args="--toast"    # one sample alert, through the real path
```

---

**Phase 5 — ship. Built and installed; not yet released.** `gradlew :app:packageMsi`
produces a 72 MB per-user installer that has been installed, run and uninstalled
on a real machine. `windows-release.yml` and the winget manifests are written and
have never run — there is no `windows-v*` tag yet.

```
gradlew :app:packageMsi          # app/build/compose/binaries/main/msi/
gradlew :app:createDistributable # the app image, no installer, no WiX
```

**No WiX to install, and no administrator.** jpackage builds an MSI by driving
WiX, which is why this phase started by asking permission to install it — a
request built on a wrong premise. The Compose Gradle plugin downloads WiX 3.11.2
into `windows/build/wix311/` and hands jpackage that copy. The installer built on
a machine with no WiX, no .NET 3.5 feature enabled, and no elevation, which also
means anyone who clones this repository can build it.

**The toast's name was never an AppUserModelID problem.** Phase 4 left this open
and guessed wrong twice: that a Start Menu shortcut would fix it, and that
`SetCurrentProcessExplicitAppUserModelID` was worth calling at all. Measured
three ways on a real install:

| | header shown |
|:--|:--|
| unpackaged `java.exe` | `OpenJDK Platform binary` |
| any build, with an explicit AppUserModelID | `com.allthingsclaude.battery`, the raw string |
| the packaged exe, no explicit ID | the app's own name and icon |

Windows reads the attribution from the executable's `FileDescription` version
resource, which jpackage writes from `nativeDistributions.description`. An
explicit AppUserModelID *overrides* that with an identifier no Start Menu entry
resolves, so the Phase 4 code made the packaged build worse. A Start Menu
shortcut turned out not to be involved either way — attribution is correct with
none installed. `Toaster` lost its only native call, and `description` is now
load-bearing: it is the name in the notification centre, which is why it reads
"Battery for Claude Code" and not something longer.

**`jdk.localedata` is the module it is easy to leave out and wrong to.** Trimming
the bundled runtime is most of why the installer is 72 MB rather than 200. Since
Java 9 `java.base` carries only root and English locale data and everything else
lives in `jdk.localedata`, so omitting it renders every non-English user's dates
in English — and *only in the packaged build*, which is the build nobody runs
while developing. Caught by rendering the panel from the installed exe and seeing
`Wed 7:00 PM` where the machine's own clock said 18:59. With the module present
the same panel reads `sre 22:31`, which is what that machine's regional format
actually is.

### Saying when, not how long

The reset caption under each gauge read `123h 47m` for a weekly window, and had
since Phase 2. Nobody converts that into a plan. `core`'s `TimeFormatting.untilReset`
exists because the same defect appeared on Apple as `153h 0m` — it renders `5d 3h`,
readable but still arithmetic.

Past a day this now says *when*: `Wed 19:00`. Below a day it stays a countdown
and defers to `TimeFormatting.shortDuration`, which is pinned across all four
platforms by `fixtures/time-formatting.json` — a five-hour session is a duration
question and `4h 37m` is the answer. `TimeFormatting` refuses absolute times for
a good reason, that a shared fixture-pinned formatter cannot carry a zone or a
locale; a desktop panel has both, so the branch lives here.

The locale it has both *from* is `Locale.Category.FORMAT`, not `Locale.getDefault()`.
Windows keeps the display language and the regional format apart and the JVM
mirrors the split: on the development machine they are `en_US` and `sr_RS_#Latn`,
so the plain default renders `7:00 PM` beside a taskbar clock reading 19:00.

**`ReleaseFeed` could not be reused unchanged.** The plan said it would be. Four
things stop it: `TAG_PREFIX` is `"android-v"`, the User-Agent is
`"Battery-Android"`, and `PER_PAGE` and `MAX_PAGES` are `internal` to the module.
The first two are closed over by `findNewer` and `newestOnPage`, so pointing this
app at them would offer a Windows user an APK. Parameterising the prefix is the
right change and it belongs **upstream**: `core` is the Android tree's module
borrowed in place, and editing it from here would diverge the very thing this
build exists to share.

So `WindowsRelease` is the page loop written once more with a different constant,
and nothing else. Everything that can actually be wrong is still `core`'s —
`compareVersions`, `itemCount`, the four-way `Check`, `shouldAnnounce`. The
duplication is about sixty lines and it is visible, which is the honest form for
a thing waiting on an upstream change.

**Two smaller things a packaging run settled.** The `application` plugin and
`compose.desktop.application` both register a `run` task and collide, so the
former is gone and the main class and console encoding moved into the compose
block. And the version is generated into `BuildInfo` rather than written twice:
`UsagePoller.VERSION` carried a literal with a note saying this phase would
replace it, and the MSI's ProductVersion would otherwise have been a second place
to remember. `releaseRepo` is generated the same way and for the reason
`android-release.yml` already documents — a fork that polls upstream reports
"up to date" for ever, which looks exactly like an updater that works.

The install itself, verified end to end: `%LOCALAPPDATA%\Battery`, a Start Menu
group, no administrator prompt, and an uninstall that leaves neither the
directory nor the shortcut behind.


The Windows 11 Widgets board — the analogue of the Glance widgets and the iOS
Home Screen widgets — is deliberately **not** in this list. It needs MSIX and a
COM server, and it is the one surface where a C#/WinUI port would have been
easier; deferring it is part of what keeps Compose Desktop the right call.

### Following the account, not just the directory

Two facts collided once a second account became plausible. Rule two holds the
credential in memory until it expires, and the identity was read once at
startup — so signing Claude Code into a different account *in the same
directory* changed nothing this app could see. The old token stayed valid for
about eight hours, so it would report the previous account's usage all
afternoon, then silently start reporting the new account's numbers under the old
account's name.

The fix is the distinction between reading a file and looking at one. Rule two
forbids the read: it is expensive, and on a stopped distro it boots a VM. A
`stat` is neither, and it is gated by `Wsl.reachable` like every other path
access here. So both files — the credential and `.claude.json` — are stamped on
each poll and re-read only when they move.

Zero is "cannot look", never "changed". A distro going down must leave the
gauges standing, which is the case `TokenCache` was built for in the first
place, and treating an unreachable path as a change would have broken it.

The same read now also carries the plan. `organizationRateLimitTier` is sitting
in `.claude.json`, which the README noted as a free local signal a phase ago; the
badge costs no request and has no failure mode, and the *label* stays `core`'s
because `default_claude_max_20x` contains "max" and a naive check calls a 20x
plan "Max".

## What this port does not have

The macOS app is the reference and this is not all of it. Written down as a list
rather than discovered one disappointment at a time.

**Several accounts at once.** macOS has `Account`, `AccountManager`,
`OAuthService` and an account-tab strip: several accounts side by side, each with
its own tokens, added by signing in to Battery itself over OAuth PKCE. That is
not here, and the omission is structural rather than unfinished. This app is a
**bridge**: it reads Claude Code's credential and never holds one of its own,
because the refresh token is deliberately withheld and rotating a single-use
chain shared with another program strands whichever copy loses. A
Battery-managed account means building the token store `UsagePoller` was written
to avoid.

Switching *between* accounts does work, by two different routes, because both
are really "which directory". Two installs signed in as different people are two
entries in the `Source` menu, since the identity comes from each directory's own
`.claude.json`. And signing one install into a different account in place is
picked up on the next poll — see the note on stamps above; before that fix it was
not, which is the more interesting half.

What is missing is only seeing both at the same time. Two accounts means two
tokens, two five-hour windows and two sets of gauges, and this panel draws one.

**Most of macOS's settings.** It has `SettingsView` and about ten preferences.
This app has two tick boxes in the tray menu and a collapsible section.
Specifically missing:

| macOS setting | here |
|:--|:--|
| `colorTheme` | **done, without the setting.** The panel follows `AppsUseLightTheme` — Windows' own "default app mode" — read once at start. Not `SystemUsesLightTheme`, which is the taskbar and Start, and which is why one can be dark while the other is light. |
| `menuBarDisplayMode`, `showMenuBarText` | absent. `TrayIconRenderer.Mode` draws all three, and the app hardcodes `RING_WITH_PERCENT`. |
| `showPercentageRemaining` | absent — always used, never remaining. |
| `showTimeSinceReset` | absent — always time until reset, never elapsed. |
| `pollIntervalActive` / `pollIntervalIdle` | absent — a fixed sixty seconds, and `PollBackoff` on failure. There is no idle cadence, so the app polls at the same rate whether or not anybody is working. |
| `launchAtLogin` | **done.** `HKCU\...\CurrentVersion\Run`, which is per-user like the installer, needs no administrator, and is what Task Manager's Startup tab reads — so turning it off there is seen here, because the state is queried rather than remembered. Never verified across an actual reboot. |
| `dataRetentionDays` | not applicable — see below. |


**A database.** macOS has `DatabaseService` and keeps snapshots across launches.
This app uses `InMemorySnapshotStore`, so the burn-rate projection — the
"8.4% per hour" line — starts from nothing on every start and needs several polls
before it says anything. Nothing else depends on history surviving a restart, and
the seven-day chart is read from Claude Code's transcripts rather than from
anything this app stores, so the gap is narrower than it sounds.

**The streak and the heat map.** `StatsView` on macOS. Deliberately not built —
neither answers a question this panel is opened to ask.

**A settings window.** Not planned. Four switches live in the tray menu and one
is the section heading itself; a window to hold them would be more chrome than
setting. If the list above ever grows past what a menu can carry, that is the
point to reconsider — not before.

## Unverified

Written down rather than discovered later:

- **`windows-release.yml` has never run.** No `windows-v*` tag exists, so the
  workflow is reasoned from `android-release.yml` and from a packaging run on one
  machine. The steps most likely to be wrong are the ones a local build cannot
  exercise: whether `windows-latest` resolves the borrowed `core` from a clean
  checkout, and whether the MSI lands where `Collect artifact` looks for it.
- **The winget manifests have never been submitted.** They are templates with a
  version and a hash to fill in; the first submission is also the first review.
- **Starting with Windows, across a real reboot.** The registry value is
  written and read back, and Task Manager lists it. Whether the app actually
  comes up at login — and whether it comes up before or after WSL is ready,
  which decides what the first poll sees — has not been watched happen.
- **The updater has never seen a real release.** `WindowsRelease` is tested
  against fixture pages, which pins the paging and the four-way answer, and says
  nothing about what GitHub actually returns for this repository's feed.
- **Whether DWM actually honours the rounded corner.** Setting
  `DWMWA_WINDOW_CORNER_PREFERENCE` to `DWMWCP_ROUND` returns `S_OK` and reads
  back as `ROUND` on the development machine, and the compositor still draws the
  flyout square — because that machine forces square corners system-wide. That
  is the correct outcome there and it is why it cannot be the test. The panel
  paints edge to edge either way, so nothing unpainted shows whichever DWM
  decides; what is unconfirmed is only whether a stock Windows 11 rounds it.
- **Every DPI other than 150%.** The scale factor is read from the screen rather
  than assumed, so 100% and 200% should follow, but only 150% has been in front
  of anyone. 100% is the interesting one: it is the case where the tray icon
  drops to a bare 16 px ring.
- **A taskbar anywhere but the bottom.** `TrayAnchor` handles a top or side
  taskbar and the geometry is tested, but the tested part is the arithmetic; what
  `Shell_NotifyIconGetRect` returns for a *vertical* taskbar has not been seen.
  The overflow case has: with the icon inside an open overflow flyout the call
  returned its position there and the panel centred on it to the pixel, which is
  the case a corner guess could never have handled.
- **A second monitor at a different scale factor.** `TrayAnchor` picks the
  screen by asking each device to read the icon rectangle with its own scale
  factor, which is exact for a monitor whose desktop origin is the origin — so
  the primary one, which is where a taskbar with a notification area normally
  is. A tray on a secondary monitor at a different scale would need the real
  mapping rather than this one.
