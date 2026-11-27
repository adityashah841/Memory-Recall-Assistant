import 'package:flutter/material.dart';

import '../../core/platform/memory_backend_channel.dart';
import 'debug_controller.dart';

/// Developer controls: service lifecycle, force tick, seed/clear, state inspect.
class DebugScreen extends StatefulWidget {
  const DebugScreen({super.key, required this.backend});

  final MemoryBackendChannel backend;

  @override
  State<DebugScreen> createState() => _DebugScreenState();
}

class _DebugScreenState extends State<DebugScreen> {
  late final DebugController _controller = DebugController(widget.backend);

  @override
  void initState() {
    super.initState();
    _controller.refresh();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Debug')),
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) {
          final state = _controller.state;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Service state',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      _StateRow(
                        label: 'Running',
                        value: state == null ? '—' : '${state.running}',
                      ),
                      _StateRow(
                        label: 'Sessions',
                        value: state == null ? '—' : '${state.sessionCount}',
                      ),
                      _StateRow(
                        label: 'People',
                        value: state == null ? '—' : '${state.personCount}',
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 8),
              if (_controller.busy) const LinearProgressIndicator(),
              if (_controller.error != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    _controller.error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed:
                        _controller.busy ? null : _controller.startCapture,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start capture'),
                  ),
                  FilledButton.tonalIcon(
                    onPressed:
                        _controller.busy ? null : _controller.stopCapture,
                    icon: const Icon(Icons.stop),
                    label: const Text('Stop capture'),
                  ),
                  OutlinedButton.icon(
                    onPressed: _controller.busy ? null : _controller.forceTick,
                    icon: const Icon(Icons.bolt),
                    label: const Text('Force tick'),
                  ),
                  OutlinedButton.icon(
                    onPressed:
                        _controller.busy ? null : _controller.seedDemoData,
                    icon: const Icon(Icons.dataset),
                    label: const Text('Seed demo data (replaces everything)'),
                  ),
                  OutlinedButton.icon(
                    onPressed:
                        _controller.busy ? null : _controller.clearAllData,
                    icon: const Icon(Icons.delete_outline),
                    label: const Text('Clear all data'),
                  ),
                  TextButton.icon(
                    onPressed: _controller.busy ? null : _controller.refresh,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh state'),
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}

class _StateRow extends StatelessWidget {
  const _StateRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
