enum ProtectionState {
  disarmed,
  armed,
  countdown,
  alarming;

  static ProtectionState parse(String? s) => switch (s) {
    'ARMED' => armed,
    'COUNTDOWN' => countdown,
    'ALARMING' => alarming,
    _ => disarmed,
  };
}

class NativeStatus {
  final ProtectionState state;
  final bool serviceRunning;
  final int remainingMs;
  final int countdownSeconds;

  const NativeStatus({
    required this.state,
    required this.serviceRunning,
    this.remainingMs = 0,
    this.countdownSeconds = 10,
  });
}