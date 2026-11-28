import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Result of submitting the dialog. error == null means success (the dialog closes).
class PinFeedback {
  final String? error;
  final int lockedMs;
  const PinFeedback.ok()
      : error = null,
        lockedMs = 0;
  const PinFeedback(this.error, {this.lockedMs = 0});
}

/// A PIN dialog that stays open on mistakes and shows the problem inside itself.
/// Pops `true` on success, `false` on cancel.
class PinDialog extends StatefulWidget {
  final String title;
  final List<String> labels; // one hidden digits-only field per label
  final Future<PinFeedback> Function(List<String> values) onSubmit;
  final int initialLockedMs;
  final String? initialMessage;

  const PinDialog({
    super.key,
    required this.title,
    required this.labels,
    required this.onSubmit,
    this.initialLockedMs = 0,
    this.initialMessage,
  });

  @override
  State<PinDialog> createState() => _PinDialogState();
}

class _PinDialogState extends State<PinDialog> {
  late final List<TextEditingController> _controllers;
  String? _message;
  bool _busy = false;
  DateTime? _lockedUntil;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _controllers = List.generate(widget.labels.length, (_) => TextEditingController());
    _message = widget.initialMessage;
    if (widget.initialLockedMs > 0) {
      _lockedUntil = DateTime.now().add(Duration(milliseconds: widget.initialLockedMs));
    }
    // Refreshes the "try again in N s" text while locked.
    _tick = Timer.periodic(const Duration(milliseconds: 500), (_) {
      if (mounted && _lockedUntil != null) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    for (final c in _controllers) {
      c.dispose();
    }
    super.dispose();
  }

  int get _lockSecs {
    final u = _lockedUntil;
    if (u == null) return 0;
    final ms = u.difference(DateTime.now()).inMilliseconds;
    return ms <= 0 ? 0 : (ms / 1000).ceil();
  }

  bool get _locked => _lockSecs > 0;

  Future<void> _submit() async {
    if (_busy || _locked) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    final fb = await widget.onSubmit(_controllers.map((c) => c.text).toList());
    if (!mounted) return;
    if (fb.error == null) {
      Navigator.pop(context, true);
      return;
    }
    for (final c in _controllers) {
      c.clear();
    }
    setState(() {
      _busy = false;
      if (fb.lockedMs > 0) {
        _lockedUntil = DateTime.now().add(Duration(milliseconds: fb.lockedMs));
        _message = null;
      } else {
        _message = fb.error;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final text = _locked ? 'Too many attempts. Try again in $_lockSecs s' : _message;
    return AlertDialog(
      title: Text(widget.title),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          for (var i = 0; i < widget.labels.length; i++)
            TextField(
              controller: _controllers[i],
              autofocus: i == 0,
              enabled: !_busy && !_locked,
              obscureText: true,
              keyboardType: TextInputType.number,
              maxLength: 8,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(labelText: widget.labels[i], counterText: ''),
              onSubmitted: (_) => _submit(),
            ),
          if (text != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(text, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
        ]),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        TextButton(
          onPressed: (_busy || _locked) ? null : _submit,
          child: const Text('OK'),
        ),
      ],
    );
  }
}