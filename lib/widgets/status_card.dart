import 'package:flutter/material.dart';
import '../models/protection_state.dart';

/// Big card that makes the current protection state obvious.
class StatusCard extends StatelessWidget {
  final NativeStatus status;

  /// True when the state claims protection but the monitoring service is dead.
  final bool showNotRunning;

  final bool starting;
  const StatusCard({super.key, required this.status, required this.showNotRunning, this.starting = false});

  (Color, IconData, String, String) _look() {
    if (starting) {
      return (Colors.blueGrey.shade600, Icons.hourglass_top, 'Starting…', 'Waiting for monitoring to start');
    }
    if (showNotRunning) {
      return (
      Colors.red.shade900,
      Icons.gpp_bad,
      'Protection NOT active',
      'Monitoring has stopped. Disarm, then arm again.',
      );
    }
    switch (status.state) {
      case ProtectionState.disarmed:
        return (Colors.blueGrey.shade600, Icons.lock_open, 'Disarmed', 'Not protecting this phone');
      case ProtectionState.armed:
        return (Colors.green.shade700, Icons.shield, 'Armed', 'Watching the SIM');
      case ProtectionState.countdown:
        return (
        Colors.orange.shade800,
        Icons.timer,
        'SIM removed',
        'Alarm in ${(status.remainingMs / 1000).ceil()} s',
        );
      case ProtectionState.alarming:
        return (Colors.red.shade700, Icons.notifications_active, 'ALARM', 'Enter your PIN to stop it');
    }
  }

  @override
  Widget build(BuildContext context) {
    final (bg, icon, title, subtitle) = _look();
    return Card(
      color: bg,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 24),
        child: Column(children: [
          Icon(icon, size: 72, color: Colors.white),
          const SizedBox(height: 12),
          Text(
            title,
            textAlign: TextAlign.center,
            style: Theme.of(context)
                .textTheme
                .headlineMedium
                ?.copyWith(color: Colors.white, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 4),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(color: Colors.white70),
          ),
        ]),
      ),
    );
  }
}