import 'package:flutter/material.dart';

import '../../core/models/person_profile.dart';
import '../../core/platform/memory_backend_channel.dart';
import '../recall/widgets/center_reticle.dart';
import 'enrollment_controller.dart';

/// Name/label an unknown person (Case B) via `enrollPerson` (Section 13.1).
class EnrollmentScreen extends StatefulWidget {
  const EnrollmentScreen({super.key, required this.backend});

  final MemoryBackendChannel backend;

  @override
  State<EnrollmentScreen> createState() => _EnrollmentScreenState();
}

class _EnrollmentScreenState extends State<EnrollmentScreen> {
  late final EnrollmentController _controller =
      EnrollmentController(widget.backend);

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
        title: const Text('Enrollment'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _controller.load,
          ),
        ],
      ),
      floatingActionButton: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => FloatingActionButton.extended(
          onPressed: _controller.enrolling ? null : () => _openNewEnrollDialog(context),
          icon: const Icon(Icons.add_a_photo),
          label: const Text('Enroll'),
        ),
      ),
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => Stack(
          children: [
            _buildList(context),
            if (_controller.enrolling) _capturingOverlay(context),
          ],
        ),
      ),
    );
  }

  Widget _buildList(BuildContext context) {
    if (_controller.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_controller.error != null) {
      return Center(child: Text(_controller.error!));
    }
    final children = <Widget>[
      if (_controller.message != null)
        Card(
          color: Theme.of(context).colorScheme.tertiaryContainer,
          child: ListTile(
            leading: const Icon(Icons.check_circle_outline),
            title: Text(_controller.message!),
          ),
        ),
      if (_controller.people.isEmpty)
        const Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'No people yet.\nTap Enroll to add someone, or seed demo data from the Debug tab.',
            textAlign: TextAlign.center,
          ),
        ),
      for (final p in _controller.people)
        _PersonTile(person: p, onEnroll: () => _openEnrollDialog(context, p)),
    ];
    return ListView(padding: const EdgeInsets.all(8), children: children);
  }

  Widget _capturingOverlay(BuildContext context) {
    return Positioned.fill(
      child: ColoredBox(
        color: Colors.black54,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: const [
            CenterReticle(state: ReticleState.detecting, label: 'Hold still — capturing 5 frames'),
          ],
        ),
      ),
    );
  }

  Future<void> _openNewEnrollDialog(BuildContext context) async {
    final nameController = TextEditingController();
    final notesController = TextEditingController();
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enroll new person'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Point the camera at the person, centered, then Capture.'),
            const SizedBox(height: 8),
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: 'Name'),
              autofocus: true,
            ),
            TextField(
              controller: notesController,
              decoration: const InputDecoration(labelText: 'Context notes'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Capture')),
        ],
      ),
    );
    if (saved == true && nameController.text.trim().isNotEmpty) {
      final notes = notesController.text.trim();
      await _controller.enrollNew(
        nameController.text.trim(),
        notes.isEmpty ? null : notes,
      );
    }
  }

  Future<void> _openEnrollDialog(
    BuildContext context,
    PersonProfile person,
  ) async {
    final nameController = TextEditingController(text: person.name ?? '');
    final notesController =
        TextEditingController(text: person.contextNotes ?? '');

    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Name person #${person.personId}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: 'Name'),
              autofocus: true,
            ),
            TextField(
              controller: notesController,
              decoration: const InputDecoration(labelText: 'Context notes'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Save'),
          ),
        ],
      ),
    );

    if (saved == true && nameController.text.trim().isNotEmpty) {
      final notes = notesController.text.trim();
      await _controller.enroll(
        person.personId,
        nameController.text.trim(),
        notes.isEmpty ? null : notes,
      );
    }
  }
}

class _PersonTile extends StatelessWidget {
  const _PersonTile({required this.person, required this.onEnroll});

  final PersonProfile person;
  final VoidCallback onEnroll;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          child: Icon(person.isNamed ? Icons.person : Icons.person_outline),
        ),
        title: Text(person.displayName),
        subtitle: person.contextNotes == null
            ? null
            : Text(person.contextNotes!, maxLines: 2, overflow: TextOverflow.ellipsis),
        trailing: TextButton(
          onPressed: onEnroll,
          child: Text(person.isNamed ? 'Edit' : 'Name'),
        ),
      ),
    );
  }
}
