import 'package:flutter/material.dart';

import '../../core/models/recall_answer.dart';
import '../../core/models/recall_context.dart';
import '../../core/platform/memory_backend_channel.dart';
import 'recall_controller.dart';
import 'widgets/evidence_card.dart';
import 'widgets/live_camera_view.dart';

/// Press-to-recall screen (Section 13.1). Renders Case A/B/C and, on Case A/C,
/// suggested questions whose answers come with cited evidence.
class RecallScreen extends StatefulWidget {
  const RecallScreen({super.key, required this.backend, this.visible = true});

  final MemoryBackendChannel backend;

  /// Whether this tab is on screen — the live camera view only polls while visible.
  final bool visible;

  @override
  State<RecallScreen> createState() => _RecallScreenState();
}

class _RecallScreenState extends State<RecallScreen> {
  late final RecallController _controller = RecallController(widget.backend);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Recall')),
      body: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => _buildBody(context),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        LiveCameraView(backend: widget.backend, active: widget.visible),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: _controller.capturing ? null : _controller.recall,
          icon: _controller.capturing
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.center_focus_strong),
          label: Text(_controller.capturing ? 'Recalling…' : 'Recall'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(56),
          ),
        ),
        const SizedBox(height: 16),
        if (_controller.error != null)
          _ErrorBanner(message: _controller.error!),
        if (_controller.context != null) ...[
          _CaseBanner(context: _controller.context!),
          const SizedBox(height: 12),
          ..._buildCaseContent(context),
        ],
      ],
    );
  }

  List<Widget> _buildCaseContent(BuildContext context) {
    final ctx = _controller.context!;
    switch (ctx.recallCase) {
      case RecallCase.unknownPerson:
        return const [
          Card(
            child: ListTile(
              leading: Icon(Icons.person_add_alt),
              title: Text('Unknown person'),
              subtitle: Text(
                'No history yet. Use the Enrollment tab to name them.',
              ),
            ),
          ),
        ];
      case RecallCase.knownPerson:
      case RecallCase.noPerson:
        return [
          _SuggestionList(
            suggestions: _controller.suggestions,
            activeId: _controller.activeSuggestion?.id,
            onTap: _controller.selectSuggestion,
          ),
          if (_controller.answering)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: Center(child: CircularProgressIndicator()),
            ),
          if (_controller.answer != null)
            _AnswerView(answer: _controller.answer!),
        ];
    }
  }
}

class _CaseBanner extends StatelessWidget {
  const _CaseBanner({required this.context});

  final RecallContext context;

  @override
  Widget build(BuildContext buildContext) {
    final (icon, title, subtitle) = switch (context.recallCase) {
      RecallCase.knownPerson => (
          Icons.face_retouching_natural,
          context.personName ?? 'Known person',
          context.locationLabel == null
              ? 'Known person'
              : 'Near ${context.locationLabel}',
        ),
      RecallCase.unknownPerson => (
          Icons.help_outline,
          'Unknown person',
          'Present but not recognised (Case B)',
        ),
      RecallCase.noPerson => (
          Icons.place_outlined,
          'No person in frame',
          context.locationLabel == null
              ? 'Searching surroundings (Case C)'
              : 'Searching around ${context.locationLabel}',
        ),
    };

    final theme = Theme.of(buildContext);
    final cue = context.recallCue;
    return Card(
      color: theme.colorScheme.secondaryContainer,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListTile(
            leading: Icon(icon),
            title: Text(title),
            subtitle: Text(subtitle),
          ),
          // §7.1 recall cue: the one warm sentence that jogs the user's memory.
          if (cue != null && cue.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Text(
                cue,
                style: theme.textTheme.titleMedium?.copyWith(
                  color: theme.colorScheme.onSecondaryContainer,
                  fontStyle: FontStyle.italic,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SuggestionList extends StatelessWidget {
  const _SuggestionList({
    required this.suggestions,
    required this.activeId,
    required this.onTap,
  });

  final List<Suggestion> suggestions;
  final String? activeId;
  final ValueChanged<Suggestion> onTap;

  @override
  Widget build(BuildContext context) {
    if (suggestions.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 8),
        child: Text('No suggestions available.'),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Text('Suggested questions'),
        ),
        for (final s in suggestions)
          Card(
            child: ListTile(
              leading: const Icon(Icons.lightbulb_outline),
              title: Text(s.questionText),
              selected: s.id == activeId,
              trailing: const Icon(Icons.chevron_right),
              onTap: () => onTap(s),
            ),
          ),
      ],
    );
  }
}

class _AnswerView extends StatelessWidget {
  const _AnswerView({required this.answer});

  final RecallAnswer answer;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        Card(
          color: Theme.of(context).colorScheme.primaryContainer,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              answer.answerText,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ),
        ),
        if (answer.evidence.isNotEmpty) ...[
          const Padding(
            padding: EdgeInsets.only(top: 12, bottom: 4),
            child: Text('Evidence'),
          ),
          for (final e in answer.evidence) EvidenceCard(evidence: e),
        ],
      ],
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Theme.of(context).colorScheme.errorContainer,
      child: ListTile(
        leading: const Icon(Icons.error_outline),
        title: Text(message),
      ),
    );
  }
}
