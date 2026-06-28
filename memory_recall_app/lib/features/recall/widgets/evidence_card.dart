import 'package:flutter/material.dart';

import '../../../core/models/recall_answer.dart';

/// Renders one cited session row (Section 10.4). Frame thumbnails are stub paths
/// in Milestone 1, so a placeholder icon stands in for the image.
class EvidenceCard extends StatelessWidget {
  const EvidenceCard({super.key, required this.evidence});

  final Evidence evidence;

  @override
  Widget build(BuildContext context) {
    final ts = evidence.timestamp;
    final when = '${ts.year}-${_two(ts.month)}-${_two(ts.day)} '
        '${_two(ts.hour)}:${_two(ts.minute)}';
    return Card(
      child: ListTile(
        leading: const CircleAvatar(child: Icon(Icons.image_outlined)),
        title: Text(evidence.transcriptSnippet ?? '(no transcript)'),
        subtitle: Text(
          [when, if (evidence.locationLabel != null) evidence.locationLabel!]
              .join(' · '),
        ),
      ),
    );
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}
