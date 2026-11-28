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
  final bool hasPin;

  const NativeStatus({
    required this.state,
    required this.serviceRunning,
    this.remainingMs = 0,
    this.countdownSeconds = 10,
    this.hasPin = false,
  });
}