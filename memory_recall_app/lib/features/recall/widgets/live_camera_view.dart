import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/platform/memory_backend_api.dart';
import '../../../core/platform/memory_backend_channel.dart';
import 'center_reticle.dart' show ReticleState;

/// Live camera view with the center-targeting overlay (IMPLEMENTATION_PLAN.md §14).
///
/// Polls the backend for the newest camera frame (a small JPEG) plus what the identity engine sees:
/// the centered face's box and whether it's known. The circle is the app's *real* targeting zone
/// (radius = `MAX_CENTER_OFFSET_FRAC` of the frame's shorter side): only a face whose center falls
/// inside it is identified. Polling runs only while [active] is true.
class LiveCameraView extends StatefulWidget {
  const LiveCameraView({super.key, required this.backend, required this.active});

  final MemoryBackendChannel backend;

  /// Poll for frames only while the view is actually on screen.
  final bool active;

  @override
  State<LiveCameraView> createState() => _LiveCameraViewState();
}

class _LiveCameraViewState extends State<LiveCameraView> {
  static const _frameInterval = Duration(milliseconds: 120);
  static const _idleInterval = Duration(seconds: 1);
  static const _maxWidth = 480;

  LivePreviewDto? _preview;
  Timer? _timer;
  bool _inFlight = false;
  bool _starting = false;

  @override
  void initState() {
    super.initState();
    if (widget.active) _schedule(Duration.zero);
  }

  @override
  void didUpdateWidget(LiveCameraView old) {
    super.didUpdateWidget(old);
    if (widget.active && !old.active) {
      _schedule(Duration.zero);
    } else if (!widget.active) {
      _timer?.cancel();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _schedule(Duration delay) {
    _timer?.cancel();
    _timer = Timer(delay, _poll);
  }

  /// One poll; reschedules itself while active (a cancellable Timer, so nothing is left pending
  /// when the view goes away).
  Future<void> _poll() async {
    if (!mounted || !widget.active || _inFlight) return;
    _inFlight = true;
    Duration next = _frameInterval;
    try {
      final p = await widget.backend.getLivePreview(_maxWidth);
      if (!mounted) return;
      setState(() => _preview = p);
      if (p.jpeg == null) next = _idleInterval;
    } catch (_) {
      next = _idleInterval;
    } finally {
      _inFlight = false;
    }
    if (mounted && widget.active) _schedule(next);
  }

  Future<void> _startCamera() async {
    setState(() => _starting = true);
    try {
      await widget.backend.startCapture();
    } catch (_) {
      // Surfaced by the placeholder staying visible.
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _preview;
    final jpeg = p?.jpeg;
    if (p == null || jpeg == null || p.width == 0) {
      return _placeholder(context);
    }
    final state = switch (p.reticle) {
      'known' => ReticleState.known,
      'unknown' => ReticleState.unknown,
      _ => ReticleState.idle,
    };
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: AspectRatio(
        aspectRatio: p.width / p.height,
        child: LayoutBuilder(
          builder: (context, box) {
            final w = box.maxWidth;
            final h = box.maxHeight;
            return Stack(
              fit: StackFit.expand,
              children: [
                Image.memory(jpeg, fit: BoxFit.cover, gaplessPlayback: true),
                CustomPaint(
                  painter: _OverlayPainter(
                    state: state,
                    centerRadius: p.centerRadius * (w < h ? w : h),
                    face: p.faceLeft == null
                        ? null
                        : Rect.fromLTWH(
                            p.faceLeft! * w,
                            p.faceTop! * h,
                            p.faceWidth! * w,
                            p.faceHeight! * h,
                          ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 12,
                  child: Center(child: _caption(state, p)),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _placeholder(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      height: 220,
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.videocam_off_outlined, size: 40),
            const SizedBox(height: 8),
            const Text('Camera is off'),
            const SizedBox(height: 12),
            FilledButton.tonalIcon(
              onPressed: _starting ? null : _startCamera,
              icon: const Icon(Icons.videocam_outlined),
              label: Text(_starting ? 'Starting…' : 'Start camera'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _caption(ReticleState state, LivePreviewDto p) {
    final (color, text) = switch (state) {
      ReticleState.known => (
          Colors.greenAccent,
          '${p.personName ?? 'Known'}'
              '${p.similarity == null ? '' : '  ·  ${(p.similarity! * 100).round()}% match'}',
        ),
      ReticleState.unknown => (Colors.redAccent, 'Unknown person'),
      _ => (Colors.white70, 'Center a face in the circle'),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color, width: 1.5),
      ),
      child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w600)),
    );
  }
}

class _OverlayPainter extends CustomPainter {
  _OverlayPainter({required this.state, required this.centerRadius, required this.face});

  final ReticleState state;
  final double centerRadius;
  final Rect? face;

  Color get _color => switch (state) {
        ReticleState.known => Colors.greenAccent,
        ReticleState.unknown => Colors.redAccent,
        ReticleState.detecting => Colors.amber,
        ReticleState.idle => Colors.white70,
      };

  @override
  void paint(Canvas canvas, Size size) {
    final color = _color;
    final center = size.center(Offset.zero);

    // Targeting zone: a face whose center is inside this circle is "the person you're facing".
    canvas.drawCircle(
      center,
      centerRadius,
      Paint()
        ..color = color.withValues(alpha: 0.9)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5,
    );
    final tick = Paint()
      ..color = color
      ..strokeWidth = 2.5
      ..strokeCap = StrokeCap.round;
    const len = 10.0;
    canvas.drawLine(center.translate(-len, 0), center.translate(len, 0), tick);
    canvas.drawLine(center.translate(0, -len), center.translate(0, len), tick);

    final f = face;
    if (f != null) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(f, const Radius.circular(10)),
        Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3,
      );
    }
  }

  @override
  bool shouldRepaint(_OverlayPainter old) =>
      old.state != state || old.centerRadius != centerRadius || old.face != face;
}
