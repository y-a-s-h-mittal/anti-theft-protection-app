# Anti-Theft Protection

A personal Android anti-theft alarm. Arm it before travelling; if the phone's SIM
subscription disappears, a native countdown starts and a loud, looping alarm plays
until the owner unlocks the phone and enters the app PIN.

> **This is a best-effort deterrent, not an undefeatable anti-theft system.**
> Android or the phone manufacturer can still stop the app, and a thief can power
> the phone off, force-stop or factory-reset it. Use it alongside the phone's
> built-in theft protection, not instead of it.

## Status

| Milestone | State |
|---|---|
| 0: SIM-removal detection spike | Done |
| 1: Core alarm (arm, countdown, alarm, PIN stop) | In progress |
| 2: Hardening (persistence, event log, reboot/battery tests) | Planned |
| 3: Remote commands (SMS / push / location) | Planned |

Tested so far on one device: **Nothing Phone (3a) Pro**, single SIM. Other devices
may behave differently.

## How it works

- **Kotlin (native)** owns the real logic: a foreground service watches SIM
  subscription changes, and (from Milestone 1) runs the countdown and alarm, so
  they keep working when the Flutter screen is closed.
- **Flutter (Dart)** is the UI: status, arm/disarm, PIN entry, settings.
- The two talk over a `MethodChannel` named `anti_theft/native`.

Removal is decided by comparing the SIM subscription captured when you armed
against the current one. Signal loss, airplane mode and mobile-data changes are
**not** treated as removal.

## Build and run

Requirements: Flutter SDK, Android Studio / Android SDK, a physical Android 13+ phone
(`minSdk` 33) with USB debugging.

```bash
git clone https://github.com/y-a-s-h-mittal/anti-theft-protection-app.git
cd anti-theft-protection-app
flutter pub get
flutter run
```

## Permissions

| Permission | Used for |
|---|---|
| `READ_PHONE_STATE` | Reading SIM / subscription state |
| `POST_NOTIFICATIONS` | The ongoing "monitoring active" notification |
| `FOREGROUND_SERVICE` (special use) | Keeping the monitor running with the app closed |

No data is uploaded. The PIN and settings are stored only on the device.

## Known limitations

- Cannot survive power-off, force-stop, uninstall or factory reset.
- Manufacturer battery management may stop the service; test on your device.
- Clearing app data removes the PIN and settings.
- Detection relies on Android reporting the subscription change; verify it on
  your own phone before relying on it.

## Docs

- [Product requirements](docs/PRD.md)
- [Milestone 0 write-up](docs/MILESTONE_0.md)

## Contributing and forking

Forks are welcome, for example to adapt the app to your own device. Please
never commit signing keys, `google-services.json`, `.env` files or your own PIN.

## License

MIT. See [LICENSE](LICENSE).
