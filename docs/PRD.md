# PRD --- Personal Anti-Theft Alarm for Android

**Status:** Draft\
**Version:** 0.1\
**Platform:** Android\
**Primary device for development and testing:** Nothing Phone (3a) Pro\
**Implementation approach:** Flutter UI + native Android/Kotlin security
service\
**Distribution:** Personal sideloaded app; not initially intended for
Play Store

------------------------------------------------------------------------

## 1. Product overview

A personal anti-theft app that can be armed before travelling. While
protection is armed, the app monitors for the disappearance of the
phone's active SIM subscription. If the SIM appears to have been
removed, the app starts a configurable countdown and then plays a loud,
repeating alarm.

The alarm must continue independently of the Flutter screen. To stop it,
the owner must unlock the phone, open the app's alarm screen, and
authenticate with the app PIN.

The app may later support authenticated remote commands over SMS and the
internet. These are secondary features and must not delay delivery of
the core SIM-removal alarm.

This is a deterrent and recovery aid, not a guarantee that a stolen
phone can be tracked, kept powered on, or prevented from being disabled.

## 2. Problem statement

If a phone is stolen, a thief may remove its SIM to prevent ordinary
cellular communication. The owner wants the phone to react locally to a
suspected SIM removal, even if the Flutter app is no longer visible and
the phone has no internet connection.

The app should make casual tampering more conspicuous by sounding an
alarm after a short delay, while requiring owner authentication to stop
that alarm.

## 3. Goals

1.  Let the owner manually arm and disarm anti-theft protection.
2.  Detect a likely disappearance of the SIM subscription that was
    present when protection was armed.
3.  Start a configurable countdown after a qualifying SIM-removal event.
4.  Play a loud, repeating alarm after the countdown.
5.  Keep countdown and alarm logic in native Android code so they do not
    depend on the Flutter activity or Dart isolate remaining alive.
6.  Require the owner to unlock the phone and enter the app PIN before
    the app stops an active alarm.
7.  Make monitoring state visible through an ongoing Android
    notification.
8.  Clearly communicate limitations, permissions, and protection status
    to the owner.

## 4. Non-goals

The first release will not attempt to:

-   Prevent the phone from being powered off, force-stopped,
    factory-reset, or uninstalled.
-   Guarantee operation after Android or the manufacturer terminates the
    app or its service.
-   Bypass Android lock-screen security or device-owner restrictions.
-   Guarantee that an alarm cannot be silenced using hardware controls,
    system settings, or other means.
-   Silently capture photographs or record audio.
-   Guarantee location retrieval or transmission when the phone is
    offline.
-   Treat loss of mobile signal, airplane mode, or loss of internet as
    proof of SIM removal.
-   Replace Google's Android theft-protection features or the phone's
    built-in security settings.

## 5. Target user and primary device

The initial user is the phone's owner, installing the app on their own
Android device.

The first implementation and acceptance testing target is a Nothing
Phone (3a) Pro running the user's installed Nothing OS / Android
version. Android version, target SDK, permission behaviour, and Nothing
OS background-management behaviour must be verified on the actual device
during development. Do not assume that behaviour documented for an older
Android version remains valid.

The app should be designed with Android platform compatibility in mind,
but broad device support is not a first-release acceptance requirement.

## 6. Primary user journey

1.  The owner opens the app before travelling.
2.  The owner has configured an alarm sound, countdown duration, and
    PIN.
3.  The owner taps **Arm protection**.
4.  The app explains and requests the permissions needed for SIM-state
    monitoring and the foreground service.
5.  The native Android component records the currently observed
    SIM/subscription baseline and starts its monitoring service while
    the app is visible.
6.  The app displays that protection is armed and an ongoing
    notification indicates that monitoring is active.
7.  The owner can leave the app and lock the phone.
8.  If the monitored SIM subscription disappears in a way the app
    classifies as a likely removal, the native service starts the
    countdown.
9.  If the SIM state returns during the countdown, the app follows the
    configured event policy. The initial default should be to cancel the
    pending countdown only if the original monitored subscription is
    restored; this behaviour must be verified and documented.
10. When the countdown expires, the native component starts the alarm.
11. The owner unlocks the phone, opens the app, enters the PIN, and taps
    **Stop alarm**.
12. The app stops playback and records the event locally. Protection
    remains disarmed until the owner explicitly arms it again.

## 7. Functional requirements

### P0 --- Required for the first usable release

#### FR-01: Arm and disarm protection

-   The owner can explicitly arm and disarm protection from the app.
-   The app must show one unambiguous state: **Disarmed**, **Armed**,
    **Countdown active**, or **Alarm active**.
-   Arming must happen from a user-visible app screen.
-   The app must not claim protection is active unless the native
    monitoring component has confirmed that it started successfully.
-   Disarming requires app authentication when an alarm is active.
    Normal disarming while no alarm is active may use the configured app
    policy, but should not be accidental.

#### FR-02: Record the SIM baseline

-   When protection is armed, the native component records the
    subscription/SIM information available through supported public
    Android APIs.
-   Store only the minimum information needed to compare the observed
    subscription state.
-   Support testing on a device with one SIM first. Dual-SIM behaviour
    is a later compatibility test.
-   Do not store or display the SIM's full phone number unless it is
    genuinely required; it should not be required for the core feature.

#### FR-03: Detect likely SIM removal

-   Monitor subscription changes using supported Android APIs, such as
    `SubscriptionManager` and its subscription-change listener, subject
    to the permissions and API behaviour of the target Android version.
-   Compare the current observed state with the baseline captured when
    protection was armed.
-   A missing subscription is a **suspected removal event**, not
    definitive proof that a physical SIM tray was ejected.
-   Do not treat mobile-data disconnection, loss of signal, or internet
    disconnection alone as SIM removal.
-   Record the observed event and timestamp for debugging.
-   The implementation must document known false-positive and
    false-negative cases.

#### FR-04: Countdown

-   Provide a configurable countdown, with a default of 30 seconds and
    an initial supported range of 10--60 seconds.
-   Run countdown logic natively, not with a Flutter/Dart timer.
-   The countdown must continue if the Flutter activity is closed or the
    screen is locked, provided Android allows the service to continue.
-   Display countdown status in the app when it is open and in the
    ongoing notification where practical.
-   Define and test what happens if the original SIM subscription
    reappears during the countdown.

#### FR-05: Alarm playback

-   Play a user-selected bundled or imported audio file when the
    countdown expires.
-   Use native Android audio APIs, not playback that depends on the
    Flutter UI.
-   Repeat playback until the owner authenticates and stops it, or until
    the operating system/device interrupts it.
-   Request and use the appropriate foreground-service type, audio
    usage, and permissions for the target Android version.
-   Handle audio focus and interruptions deliberately. Do not promise
    that the app can override system volume, Do Not Disturb, hardware
    buttons, or every system audio policy.
-   Provide a test-alarm function so the owner can verify sound and
    volume before relying on the feature.

#### FR-06: PIN-protected alarm cancellation

-   The owner must unlock the device, open the app, and enter the app
    PIN to stop an active alarm.
-   The app must not store the PIN as plaintext.
-   Store a salted password hash using a suitable password-hashing
    approach available for Android; do not invent a cryptographic
    scheme.
-   Add basic retry throttling and avoid revealing whether a partial PIN
    was correct.
-   If the app data is cleared or the app is uninstalled, the PIN and
    local state may be lost; this limitation must be documented.

#### FR-07: Persistent status notification

-   While protection is armed, show an ongoing notification that
    accurately reflects monitoring status.
-   The notification must not expose the PIN, secret command tokens, or
    sensitive location information.
-   Tapping it should open the app's status screen.
-   If monitoring stops unexpectedly and the app can detect that fact,
    show an appropriate status rather than silently claiming protection
    continues.

#### FR-08: Local event log

-   Store a small local log of arming, disarming, suspected SIM changes,
    countdown start/cancel, alarm start, and alarm stop.
-   Include timestamps and diagnostic state, but avoid unnecessary
    personal or SIM data.
-   Provide a way to clear the log.

### P1 --- Remote commands and recovery support

These features are intentionally excluded from the first milestone.

#### FR-09: Authenticated SMS commands

-   Allow the owner to configure one or more trusted sender numbers.
-   Support a small set of commands, initially: `STATUS`, `ALARM`, and
    `LOCATE`.
-   Authenticate commands using a high-entropy secret or
    message-authentication token, not a predictable keyword or the app
    PIN.
-   Include unique command identifiers, expiry, and replay protection.
-   Never execute a command solely because its body contains a keyword.
-   Explain that SMS commands cannot reach the phone if its SIM is
    removed and it has no other connectivity.
-   Confirm the exact Android SMS permissions and restrictions for the
    target OS before implementation. Sideloading does not remove
    Android's runtime permission or background-execution restrictions.

#### FR-10: Internet-based remote commands

-   Consider Firebase Cloud Messaging (FCM) or another suitable push
    mechanism.
-   Accept commands only after cryptographic/authentication checks.
-   Design for delayed, duplicated, downgraded, or undelivered push
    messages.
-   Internet commands work only when the device has usable connectivity
    and the app has not been force-stopped or otherwise prevented from
    running.

#### FR-11: Location reporting

-   With explicit prior permission, obtain a location fix and send it to
    a secure owner-controlled endpoint.
-   Request background location only if the chosen implementation
    genuinely requires it and the user understands why.
-   Clearly handle location disabled, permission revoked, no GPS fix, no
    network, and stale coordinates.
-   Do not claim a location is live if it is old or cached.
-   Never expose location to an unauthenticated endpoint.

### P2 --- Research only; not committed

#### FR-12: Camera capture

-   Investigate whether a user-initiated, properly declared camera
    foreground service can capture an image under the required
    locked-screen conditions on the target Android version.
-   Treat this as an experiment, not a promised feature.
-   Camera permission alone does not grant unrestricted background
    camera access.
-   Do not implement covert camera capture or audio recording.
-   Do not make camera functionality a dependency of the anti-theft
    alarm.

## 8. Non-functional requirements

### Reliability

-   The countdown and alarm state machine must be native and independent
    of Flutter UI lifecycle.
-   Persist enough state to recover sensibly after process recreation.
-   Use appropriate Android foreground-service declarations and
    permissions for the actual work being performed.
-   Start the monitoring foreground service from a user-visible activity
    when protection is armed.
-   Test behaviour under screen lock, app navigation away, activity
    destruction, process recreation, battery optimisation, reboot, and
    force-stop.
-   Never describe a foreground service as impossible for Android or the
    manufacturer to stop.

### Security and privacy

-   Collect the minimum data needed.
-   Keep the PIN hash and remote-command secrets out of source control.
-   Do not hardcode API keys or command secrets in the repository.
-   Use encrypted storage for sensitive local secrets where appropriate.
-   Use HTTPS for remote communication.
-   Validate sender identity and authenticate remote commands
    cryptographically.
-   Do not log secrets, PINs, full message contents, or unnecessary
    location data.
-   The app must not upload data unless the owner has configured and
    enabled that feature.

### Usability

-   The main screen must make the current protection state obvious.
-   Permission explanations must say what each permission is used for.
-   If a required permission is missing or monitoring cannot start, the
    app must show that protection is not active.
-   Settings should use plain language and sensible defaults.

### Maintainability

-   Keep Android-specific monitoring, countdown, and alarm logic in a
    small native Kotlin module/service.
-   Keep Flutter responsible for configuration, status display, PIN
    entry, and user interaction.
-   Use a documented Flutter `MethodChannel` (or an equivalent
    maintained platform integration) for commands and state updates.
-   Avoid putting critical background behaviour in a Dart isolate or
    relying on a Flutter plugin to keep the app alive.

## 9. Proposed technical architecture

### Flutter layer

Responsibilities: - Main status screen - Arm/disarm controls - PIN setup
and entry - Countdown configuration - Alarm sound selection and test -
Permission education and status - Local event-log display - Future
remote-command configuration

### Native Android/Kotlin layer

Responsibilities: - Foreground monitoring service -
Subscription/SIM-state observation - Baseline comparison - Countdown
state machine - Native alarm playback - PIN-verification handoff or
secure verification boundary - Persistent state and event recording -
Future SMS receiver, location work, and push-command handling

### Flutter ↔ Android bridge

-   Define a small, versioned method/event contract.
-   Flutter can request operations such as `arm`, `disarm`, `stopAlarm`,
    `getStatus`, and `testAlarm`.
-   Native Android code remains authoritative for armed/countdown/alarm
    state.
-   Do not assume Flutter is running when a SIM event occurs.
-   On app launch, Flutter must query native state rather than trusting
    stale UI state.

### State model

``` text
DISARMED
   |
   | Owner arms protection
   v
ARMED
   |
   | Suspected monitored SIM disappearance
   v
COUNTDOWN
   |                 |
   | Original SIM    | Countdown expires
   | restored        v
   |              ALARMING
   |                 |
   |                 | Owner unlocks device,
   |                 | enters correct app PIN
   |                 v
   +-------------- DISARMED
```

The exact transition for SIM restoration during countdown must be
validated and fixed in the implementation specification before coding.

## 10. Permissions and Android platform constraints

The exact manifest declarations and runtime permission flow must be
decided against the target SDK and the Android version installed on the
test phone. Likely areas to investigate include:

-   Phone-state/subscription access needed by the chosen SIM-monitoring
    API.
-   Notification permission and notification-channel setup.
-   Foreground-service permission and the correct foreground-service
    type.
-   Audio playback behaviour and any newer Android background-audio
    restrictions.
-   Boot receiver restrictions if reboot recovery is added.
-   SMS permissions for the optional SMS feature.
-   Location and background-location permissions for optional location
    reporting.
-   Camera foreground-service and while-in-use restrictions for any
    future camera experiment.

Important constraints: - Android restricts starting foreground services
from the background in many situations. Start the core monitoring
service as part of the owner's visible **Arm protection** action. -
Android versions continue to add foreground-service type and
background-execution restrictions. Re-check current official Android
documentation before implementation; do not copy an old tutorial's
manifest and assume it is valid. - A normal app cannot reliably prevent
force-stop, shutdown, uninstall, or factory reset. - Reboot recovery is
best-effort and must be tested against current Android restrictions. -
Nothing OS may apply additional battery/background management. Test the
actual phone rather than assuming stock Android behaviour. - Public
Android APIs may report subscription changes but do not provide a
universally reliable physical SIM-tray-ejection event. The feature is
therefore based on a likely subscription disappearance and must be
tested on-device.

## 11. Acceptance criteria for the first milestone

The first milestone is successful only if all of the following work on
the target phone:

1.  The app can be installed and opened.
2.  The owner can set a PIN and select an alarm sound.
3.  The owner can arm protection while the app is visible.
4.  The app confirms that the native monitoring service started.
5.  The owner can leave the app and lock the phone.
6.  Physically removing the monitored SIM causes the native component to
    detect a qualifying subscription disappearance.
7.  The configured countdown starts and runs without the Flutter screen
    being open.
8.  The alarm starts when the countdown expires.
9.  The alarm continues after the Flutter activity is closed, subject to
    documented OS/device limits.
10. The owner can unlock the phone, open the app, enter the correct PIN,
    and stop the alarm.
11. An incorrect PIN does not stop the alarm.
12. The app reports failures honestly when permissions are missing or
    the service cannot start.

A test is not considered passed merely because it works while the app is
open.

## 12. Test plan

Test on the physical Nothing Phone (3a) Pro. Use a spare SIM if
possible.

  -----------------------------------------------------------------------
  Test                                Expected result
  ----------------------------------- -----------------------------------
  Arm with SIM present                Native service starts; state
                                      becomes Armed

  Close Flutter activity              Monitoring remains active under
                                      normal OS conditions

  Lock screen                         Monitoring continues under normal
                                      OS conditions

  Remove SIM while armed              A qualifying subscription change is
                                      observed, or the limitation is
                                      recorded

  Wait through countdown              Alarm starts at the configured time

  Enter wrong PIN                     Alarm continues

  Enter correct PIN                   Alarm stops and state becomes
                                      Disarmed

  Restore SIM during countdown        Behaviour matches the explicitly
                                      chosen policy

  Toggle airplane mode                Must not be misclassified as
                                      physical SIM removal without
                                      supporting evidence

  Lose mobile signal                  Must not automatically be treated
                                      as SIM removal

  Reboot phone                        Record whether and how recovery
                                      works; do not assume it does

  Force-stop app                      Record expected Android behaviour;
                                      app may not resume until manually
                                      launched

  Disable a required permission       App reports protection unavailable
                                      or degraded

  Enable battery optimisation /       Record whether monitoring is
  manufacturer restrictions           affected
  -----------------------------------------------------------------------

Also test false positives, delayed subscription callbacks, SIM
reinsertion, and repeated SIM-state changes.

## 13. Delivery milestones

### Milestone 0 --- Technical spike

-   Create a minimal Flutter app with a Kotlin platform bridge.
-   Start and stop a native foreground service from a visible screen.
-   Observe subscription changes on the physical phone.
-   Document the exact permissions and observed callback behaviour.
-   Do not build remote features yet.

**Exit condition:** Demonstrate whether the target phone exposes a
usable subscription-change signal when the physical SIM is removed and
reinserted.

### Milestone 1 --- Core alarm prototype

-   Arm protection.
-   Record SIM baseline.
-   Detect likely disappearance.
-   Run native countdown.
-   Play native repeating alarm.
-   Stop alarm through PIN-authenticated app flow.

**Exit condition:** Pass the core acceptance flow with the Flutter
activity closed and screen locked.

### Milestone 2 --- Hardening

-   Improve state persistence and error handling.
-   Add event log and clear status reporting.
-   Test process recreation, reboot, battery restrictions, and
    permission revocation.
-   Verify current Android 17 / target-SDK requirements on the actual
    device.

### Milestone 3 --- Remote commands

-   Add authenticated SMS commands only after the local alarm is stable.
-   Add internet push commands if useful.
-   Add secure location reporting.
-   Test offline and adversarial cases.

### Milestone 4 --- Research features

-   Investigate camera feasibility separately.
-   Add only if the platform permits the intended behaviour reliably and
    transparently.

## 14. Key risks

  --------------------------------------------------------------------------
  Risk                    Impact                  Mitigation
  ----------------------- ----------------------- --------------------------
  Android does not expose Core trigger may be     Validate
  a reliable physical     imperfect               subscription-change
  SIM-ejection event                              behaviour on the target
                                                  phone before building the
                                                  full app

  Nothing OS stops or     Monitoring may stop     Test manufacturer battery
  restricts the service                           settings and report
                                                  monitoring health honestly

  New Android background  Service or audio may    Target current APIs and
  restrictions            not start as expected   verify
                                                  foreground-service/audio
                                                  rules for the target SDK

  False SIM-removal       Unwanted alarm          Use a state-based
  detection                                       detector, debounce
                                                  transient changes, and
                                                  test signal/airplane-mode
                                                  cases

  Alarm is silenced or    Deterrent is weakened   Document limits; do not
  interrupted by system                           claim system-level control
  controls                                        

  Phone is powered off or App cannot react        Treat built-in theft
  force-stopped                                   protection and device
                                                  security as separate
                                                  layers

  SMS unavailable after   Remote command cannot   Add internet command
  SIM removal             arrive                  channel as a separate
                                                  optional path

  Remote command spoofing Attacker could trigger  Use strong authentication,
  or replay               actions                 expiry, unique IDs, and
                                                  replay protection

  Camera background       Silent capture may not  Keep camera out of the
  restrictions            work                    core release; perform a
                                                  separate feasibility spike
  --------------------------------------------------------------------------

## 15. Open decisions

These should be decided before the relevant implementation stage:

1.  Should restoring the original SIM during the countdown cancel the
    countdown, or should any detected removal event remain latched until
    the PIN is entered?
2.  Should the default countdown be 20 or 30 seconds? The current
    proposed default is 30 seconds.
3.  Should protection automatically disarm after a successful alarm
    cancellation, or should it return to Armed? The proposed
    first-release behaviour is Disarmed to avoid an immediate repeat
    alarm.
4.  Should a future remote alarm command trigger immediately or use the
    same countdown?
5.  Which secure backend, if any, should be used for internet commands
    and location reporting?

## 16. Definition of success

The first release is successful if it reliably demonstrates the core
local sequence on the target phone:

**Arm → close app → lock phone → remove SIM → countdown → loud alarm →
unlock → enter PIN → stop alarm.**

The project must clearly communicate that Android and
device-manufacturer controls can still interrupt or prevent operation.
The app is a best-effort personal deterrent, not an undefeatable
anti-theft system.
