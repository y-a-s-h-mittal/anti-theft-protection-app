import 'package:flutter/material.dart';
import '../services/native_bridge.dart';

/// Explains each permission before Android asks for it.
/// Returns true only if everything needed is granted.
Future<bool> ensurePermissions(BuildContext context) async {
  final p = await NativeBridge.permissionStatus();
  if (p.all) return true;
  if (!context.mounted) return false;

  final go = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Permissions needed'),
      content: const Text(
        'SIM Sentinel needs two permissions:\n\n'
            '• Phone: to see whether your SIM is present. Android may word this as '
            '"make and manage phone calls", but the app only reads SIM state. It never '
            'reads your number or places calls.\n\n'
            '• Notifications: to show the ongoing "armed" notification that Android '
            'requires for background monitoring.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Not now')),
        TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Continue')),
      ],
    ),
  );
  if (go != true) return false;
  if (await NativeBridge.requestPermissions()) return true;
  if (!context.mounted) return false;

  final open = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Protection cannot work yet'),
      content: const Text(
        'The permissions were not granted. You can allow Phone and Notifications '
            'in the app\'s system settings.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
        TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Open settings')),
      ],
    ),
  );
  if (open == true) await NativeBridge.openAppSettings();
  return false;
}