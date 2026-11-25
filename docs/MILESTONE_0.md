# Milestone 0: SIM Subscription-Change Spike

**Goal:** prove that the target phone (Nothing Phone (3a) Pro) reports a usable signal when the physical SIM is removed and reinserted, and that a native Android foreground service can observe it while the Flutter UI is closed, the screen is locked, or the phone is idle.

**Result: passed.** The signal is reliable and distinguishable from airplane mode. Details in [Test results](#5-test-results).

---

## 1. How the app is split

| World | Language | Responsibility | Lives while the UI is closed? |
|---|---|---|---|
| **Brain** | Kotlin (`android/.../kotlin/`) | Foreground service, SIM observation, (later) countdown, alarm, PIN | **Yes** |
| **Face** | Dart/Flutter (`lib/`) | Buttons, status display, PIN entry, settings | No |
| **Bridge** | `MethodChannel "anti_theft/native"` | Dart sends a method name, Kotlin replies | n/a |

Rule: **Kotlin is the source of truth.** Flutter asks Kotlin for state and never trusts its own stale copy.

---

## 2. Glossary (Android terms used below)

- **Context**: handle to the Android system (files, services, system managers). Activities and Services are Contexts.
- **Service**: a component that runs without a screen.
- **Foreground service**: a Service with a visible, ongoing notification. Android is far less likely to kill it, and it can keep running after the app is swiped away.
- **Manifest** (`AndroidManifest.xml`): the file declaring permissions and components to Android.
- **SharedPreferences**: small key-value file on disk; survives process death.
- **Executor**: object that decides which thread runs a callback. We use the main thread's.
- **MethodChannel**: Flutter's named pipe to native code.
- **Subscription**: Android's record of an active SIM line (has an integer `subscriptionId`).

---

## 3. Files added and what each one does

### 3.1 `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
```

| Permission | Why |
|---|---|
| `READ_PHONE_STATE` | Needed to read the active subscription list and SIM state. Runtime permission (user must tap Allow). |
| `POST_NOTIFICATIONS` | Android 13+ requires this to show the foreground-service notification. Runtime permission. |
| `FOREGROUND_SERVICE` | Allows running a foreground service at all. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ forces every foreground service to declare a *type*. None of the standard types (camera, location, media...) describes SIM monitoring, so `specialUse` is used. |

```xml
<service
    android:name=".service.MonitoringService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Monitors SIM subscription state for a personal anti-theft alarm"/>
</service>
```

- `android:name=".service.MonitoringService"` is a *package-relative class path*. The Kotlin folder must be named `service`, or Android cannot find the class and the app crashes on start.
- `exported="false"`: only this app can start the service.
- The `<property>` is a plain-language justification for `specialUse`.

`minSdk` is set to 33 in `android/app/build.gradle.kts`: this is a personal app for one phone, and it matches `POST_NOTIFICATIONS`.

---

### 3.2 `state/SpikeLog.kt` (temporary)

A tiny logger that writes the last 100 lines to SharedPreferences **and** to Logcat (`adb logcat -s SimSpike`).

Why not just `Log.d`? Logcat is lost when the phone is unplugged or the buffer rolls. SharedPreferences persists, so after a test you can open the app and read what happened while the UI was dead.

- `add(ctx, msg)`: prefixes a timestamp, appends, keeps only the last 100 lines.
- `read(ctx)`: returns the whole log as a string.
- `clear(ctx)`: wipes it.

Replaced by `EventLogStore` in Milestone 2.

---

### 3.3 `sim/SimMonitor.kt` (the heart of the spike)

```kotlin
private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
    override fun onSubscriptionsChanged() {
        SpikeLog.add(ctx, "CALLBACK onSubscriptionsChanged -> ${describe()}")
    }
}
```

**Key idea: the callback only says "something changed", not what.** So the callback never decides anything; it triggers `describe()`, which re-reads the real state:

```kotlin
fun describe(): String {
    val subs = sm.activeSubscriptionInfoList.orEmpty()
    // activeSubs=<count> [id=<subscriptionId>/slot=<slotIndex>] simState(slot0)=<code>
}
```

- `activeSubscriptionInfoList`: which subscriptions are currently active. Empty list = no active SIM.
- `TelephonyManager.getSimState(0)`: low-level SIM card state for slot 0. `1` = ABSENT, `5` = READY.
- A `try/catch SecurityException` reports a missing permission instead of crashing.

`start()` logs a **baseline** and registers the listener with `addOnSubscriptionsChangedListener(executor, listener)`. `stop()` unregisters it (forgetting this leaks the listener).

---

### 3.4 `service/MonitoringService.kt`

The long-lived container for `SimMonitor`.

1. `onCreate()` creates the `SimMonitor`.
2. `onStartCommand()`:
   - Creates a notification channel (required on Android 8+), importance LOW (no sound).
   - Builds an **ongoing** notification ("Protection monitoring (spike)").
   - Calls `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`. This must happen within ~5 seconds of `startForegroundService()`, or Android kills the app.
   - Starts the monitor once; the `running` flag prevents registering the listener twice if Start is tapped repeatedly.
3. `START_STICKY`: asks Android to recreate the service if it kills it (best effort, not a guarantee).
4. `onDestroy()`: stops the monitor and clears `running`.
5. `onBind` returns `null`: nobody binds to this service; it is only started/stopped.

`running` is a `@Volatile` static so the UI can ask "is the service alive?" via the bridge.

---

### 3.5 `MainActivity.kt` (the bridge, native side)

Registers a `MethodChannel` named `anti_theft/native` and maps method names to actions:

| Method | Action |
|---|---|
| `requestPermissions` | Asks for `READ_PHONE_STATE` + `POST_NOTIFICATIONS` for anything not yet granted |
| `startService` / `stopService` | Starts (as a foreground service) / stops `MonitoringService` |
| `isRunning` | Returns `MonitoringService.running` |
| `getLog` / `clearLog` | Reads/clears `SpikeLog` |

**Why `requestPermissions` stores `pendingResult`:** the permission dialog is asynchronous. Kotlin can't answer the Dart call immediately, so it keeps the reply handle and completes it in `onRequestPermissionsResult` once the user taps Allow/Deny.

**Why permissions are requested natively:** the `permission_handler` Flutter plugin failed to build with the project's Gradle/AGP version (its own build script didn't compile). Requesting two permissions in Kotlin is ~15 lines, has no dependency, and keeps Android-specific logic in Kotlin as the PRD requires.

---

### 3.6 `lib/services/native_bridge.dart`

The only Dart file that knows the channel name and method strings. Screens call typed functions (`NativeBridge.startService()`); if a method name changes, only this file changes.

### 3.7 `lib/screens/home_screen.dart` (throwaway spike UI)

- `initState`: loads the log and starts a 1-second `Timer.periodic` poll.
- **Start** button: `requestPermissions()` then `startService()`; if permissions are denied it shows a SnackBar and does **not** start (the PRD says never claim protection when it isn't active).
- Stop / Clear log buttons, and a selectable monospace log view.
- `dispose` cancels the timer (avoids leaking it).

### 3.8 Placeholder stubs

Created now to lock in the folder layout; filled in Milestone 1+: `AlarmPlayer`, `CountdownManager`, `ChannelHandler`, `PinStore`, `ProtectionState`, `EventLogStore`; and Dart `protection_state.dart`, `event_log_entry.dart`, `pin_screen.dart`, `settings_screen.dart`, `event_log_screen.dart`, `status_card.dart`.

---

## 4. File structure after Milestone 0

```
lib/
  main.dart
  models/        protection_state.dart, event_log_entry.dart
  screens/       home_screen.dart, pin_screen.dart, settings_screen.dart, event_log_screen.dart
  services/      native_bridge.dart
  widgets/       status_card.dart
android/app/src/main/
  AndroidManifest.xml
  kotlin/com/example/anti_theft_protection/
    MainActivity.kt
    alarm/       AlarmPlayer.kt, CountdownManager.kt
    bridge/      ChannelHandler.kt
    service/     MonitoringService.kt
    sim/         SimMonitor.kt
    state/       EventLogStore.kt, PinStore.kt, ProtectionState.kt, SpikeLog.kt
docs/            PRD.md, MILESTONE_0.md
```

---

## 5. Test results

Device: Nothing Phone (3a) Pro, one SIM, subscription `id=4`.

SIM state codes seen: `5` = READY, `1` = ABSENT.

| # | Test | Observed |
|---|---|---|
| 0 | Start | Notification shown; baseline `activeSubs=1 [id=4/slot=0] simState=5` |
| 1 | Remove SIM (app open) | 2 callbacks ~6 ms apart, `activeSubs=0 []`, `simState=1` |
| 2 | Reinsert | ~11 callbacks over ~3 s, all `activeSubs=1 [id=4]`, `simState=5` |
| 3 | App swiped from recents, remove SIM | Removal still logged; same process ID throughout |
| 4 | Screen locked ~2 min, remove SIM | Removal logged (2 callbacks, `activeSubs=0`, `simState=1`) |
| 5 | Airplane mode on/off | Callbacks fire, but state stays `activeSubs=1`, `simState=5` (**no false positive**) |
| 6 | ~16 min unplugged idle, then remove SIM | Removal logged at once (2 callbacks); reinsertion logged; service still alive |

### What the logs teach us (design rules for Milestone 1)

1. **A callback is a ping, not an event.** Airplane mode fires callbacks with no change; reinsertion fires ~11. Always re-read the state.
2. **Removal rule:** the *baseline subscription ID is missing from `activeSubscriptionInfoList`*. This also catches a thief swapping in a different SIM (list size 1, but a different ID). `simState == ABSENT` is supporting evidence.
3. **Debounce:** wait ~1.5 s and confirm the state is still missing before starting the countdown.
4. **Restoration:** the same ID returns on reinsertion, so "original SIM restored" is detectable. Require it to be stable ~2 s before cancelling a countdown.
5. **Timers:** `Handler.postDelayed` counts awake time and can stall in CPU sleep. Use an absolute deadline (`SystemClock.elapsedRealtime() + N`) plus a partial wake lock.

### Known limits of this spike

- 16 minutes is shorter than deep Doze (typically 30-60+ minutes stationary). An overnight run belongs in Milestone 2.
- Log timestamps show when a callback was *delivered*, not when the SIM was pulled; a short delivery delay would be invisible.
- Single SIM only. Dual-SIM is a later compatibility test.
- Reboot recovery and force-stop behaviour are not tested yet.

---

## 6. Problems hit and how they were fixed

| Problem | Cause | Fix |
|---|---|---|
| IDE showed "Unresolved reference" everywhere | Files were empty/incomplete, so the IDE could not resolve imports | Pasted the full code; `flutter run` is the real compiler check |
| `services` vs `service` | Manifest and package declarations said `service`; folder was `services` | Renamed folder to `service` |
| Gradle error inside `permission_handler_android` | Plugin's build script incompatible with the project's Gradle | Removed the plugin; permissions requested in Kotlin |
| Nullability warning on `getString` | Platform call returns a nullable type | Give it an explicit type / handle null |

---

## 7. Git and repository hygiene

- `.gitignore` merged from Flutter's generated file plus secret/signing patterns (`*.jks`, `*.keystore`, `key.properties`, `google-services.json`, `.env*`, `local.properties`).
- `pubspec.lock` is **committed** (this is an app, not a library).
- `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` are not committed; Flutter regenerates them.
- No secrets exist in the repo: the PIN will be set on the device and stored only there.

---

## 8. Next: Milestone 1: core alarm

Arm → detect → countdown → alarm → PIN stop, all native.

Decisions locked in: SIM restored mid-countdown → cancel and return to **ARMED**; default countdown **30 s** (range 10-60); after PIN stop → **DISARMED**.

Build order: `ProtectionState` + persistence → debounced `SimMonitor` → `CountdownManager` (wake lock) → `AlarmPlayer` → `PinStore` → `ChannelHandler`, bridge and real Home/PIN screens.
