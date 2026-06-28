import 'package:flutter/material.dart';

import '../../core/models/memory_session.dart';
import '../../core/platform/memory_backend_channel.dart';
import 'timeline_controller.dart';

/// Paged list of stored sessions (Section 13.1).
class TimelineScreen extends StatefulWidget {
  const TimelineScreen({super.key, required this.backend});

  final MemoryBackendChannel backend;

  @override
  State<TimelineScreen> createState() => _TimelineScreenState();
}

class _TimelineScreenState extends State<TimelineScreen> {
  late final TimelineController _controller = TimelineController(widget.backend);

  @override
  void initState() {
    super.initState();
    _controller.load();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Timeline'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _controller.load,
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) {
          if (_controller.loading) {
            return const Center(child: CircularProgressIndicator());
          }
          if (_controller.error != null) {
            return Center(child: Text(_controller.error!));
          }
          if (_controller.sessions.isEmpty) {
            return const _EmptyState();
          }
          return RefreshIndicator(
            onRefresh: _controller.load,
            child: ListView.builder(
              itemCount: _controller.sessions.length,
              itemBuilder: (context, i) =>
                  _SessionTile(session: _controller.sessions[i]),
            ),
          );
        },
      ),
    );
  }
}

class _SessionTile extends StatelessWidget {
  const _SessionTile({required this.session});

  final MemorySession session;

  @override
  Widget build(BuildContext context) {
    final ts = session.timestamp;
    final when = '${ts.year}-${_two(ts.month)}-${_two(ts.day)} '
        '${_two(ts.hour)}:${_two(ts.minute)}';
    return ListTile(
      leading: CircleAvatar(
        child: Icon(session.hasPerson ? Icons.person : Icons.place_outlined),
      ),
      title: Text(session.subjectLabel),
      subtitle: Text(
        [
          when,
          if (session.locationLabel != null) session.locationLabel!,
          if (session.transcript != null) session.transcript!,
        ].join(' · '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
    );
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(24),
        child: Text(
          'No sessions yet.\nSeed demo data from the Debug tab.',
          textAlign: TextAlign.center,
        ),
      ),
    );
  }
}
