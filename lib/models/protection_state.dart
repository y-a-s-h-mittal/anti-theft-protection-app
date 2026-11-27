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
  const NativeStatus(this.state, this.serviceRunning);
}