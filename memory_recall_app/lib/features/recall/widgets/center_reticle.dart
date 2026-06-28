import 'package:flutter/material.dart';

/// Targeting state for the center reticle (IMPLEMENTATION_PLAN.md §3.5, §14).
enum ReticleState {
  /// No face near the frame center (Case C). Grey.
  idle,

  /// Face locked near center, embedding/identifying in progress. Yellow, pulsing.
  detecting,

  /// Known person identified (Case A). Green + name + score.
  known,

  /// Centered face does not match any profile (Case B). Red.
  unknown,
}

/// Center-targeting overlay drawn over the camera preview so the center-crop behavior is visible
/// (§14). Driven by the recall/capture layer (CP6/CP7); standalone and stateless apart from the
/// pulse animation used in [ReticleState.detecting].
class CenterReticle extends StatefulWidget {
  const CenterReticle({
    super.key,
    required this.state,
    this.label,
    this.score,
    this.diameter = 180,
  });

  /// Current targeting state.
  final ReticleState state;

  /// Person name to show when [state] is [ReticleState.known].
  final String? label;

  /// Cosine similarity (0..1) to render as a bar when known.
  final double? score;

  /// Reticle circle diameter in logical pixels.
  final double diameter;

  @override
  State<CenterReticle> createState() => _CenterReticleState();
}

class _CenterReticleState extends State<CenterReticle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulse = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  Color get _color {
    switch (widget.state) {
      case ReticleState.idle:
        return Colors.grey;
      case ReticleState.detecting:
        return Colors.amber;
      case ReticleState.known:
        return Colors.green;
      case ReticleState.unknown:
        return Colors.red;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _color;
    return IgnorePointer(
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedBuilder(
              animation: _pulse,
              builder: (context, _) {
                final opacity = widget.state == ReticleState.detecting
                    ? 0.4 + 0.6 * _pulse.value
                    : 1.0;
                return CustomPaint(
                  size: Size.square(widget.diameter),
                  painter: _ReticlePainter(color.withOpacity(opacity)),
                );
              },
            ),
            const SizedBox(height: 12),
            _caption(color),
          ],
        ),
      ),
    );
  }

  Widget _caption(Color color) {
    switch (widget.state) {
      case ReticleState.idle:
        return const SizedBox.shrink();
      case ReticleState.detecting:
        return _pill(color, 'Identifying…');
      case ReticleState.unknown:
        return _pill(color, 'Unknown — tap to enroll');
      case ReticleState.known:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _pill(color, widget.label ?? 'Known'),
            if (widget.score != null) ...[
              const SizedBox(height: 6),
              _scoreBar(color, widget.score!.clamp(0.0, 1.0)),
            ],
          ],
        );
    }
  }

  Widget _pill(Color color, String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.black.withOpacity(0.55),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color, width: 1.5),
      ),
      child: Text(
        text,
        style: TextStyle(color: color, fontWeight: FontWeight.w600),
      ),
    );
  }

  Widget _scoreBar(Color color, double value) {
    return SizedBox(
      width: 160,
      child: Row(
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: value,
                minHeight: 6,
                backgroundColor: Colors.white24,
                valueColor: AlwaysStoppedAnimation<Color>(color),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '${(value * 100).round()}%',
            style: TextStyle(color: color, fontSize: 12),
          ),
        ],
      ),
    );
  }
}

class _ReticlePainter extends CustomPainter {
  _ReticlePainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final radius = size.width / 2;
    final ring = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3;
    canvas.drawCircle(center, radius, ring);

    // Corner ticks + a small center crosshair to read as a "target".
    final tick = Paint()
      ..color = color
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    const len = 14.0;
    canvas.drawLine(center.translate(-len, 0), center.translate(-4, 0), tick);
    canvas.drawLine(center.translate(4, 0), center.translate(len, 0), tick);
    canvas.drawLine(center.translate(0, -len), center.translate(0, -4), tick);
    canvas.drawLine(center.translate(0, 4), center.translate(0, len), tick);
  }

  @override
  bool shouldRepaint(_ReticlePainter old) => old.color != color;
}
