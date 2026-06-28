import '../platform/memory_backend_api.dart';

/// A suggested question (Section 10.4).
class Suggestion {
  const Suggestion({required this.id, required this.questionText});

  final String id;
  final String questionText;

  factory Suggestion.fromDto(SuggestionDto dto) =>
      Suggestion(id: dto.id, questionText: dto.questionText);
}

/// One cited session row backing an answer (Section 10.4).
class Evidence {
  const Evidence({
    required this.sessionId,
    required this.timestampMs,
    this.locationLabel,
    this.frameThumbPath,
    this.transcriptSnippet,
  });

  final int sessionId;
  final int timestampMs;
  final String? locationLabel;
  final String? frameThumbPath;
  final String? transcriptSnippet;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  factory Evidence.fromDto(EvidenceDto dto) => Evidence(
        sessionId: dto.sessionId,
        timestampMs: dto.timestampMs,
        locationLabel: dto.locationLabel,
        frameThumbPath: dto.frameThumbPath,
        transcriptSnippet: dto.transcriptSnippet,
      );
}

/// A grounded answer plus its cited evidence.
class RecallAnswer {
  const RecallAnswer({required this.answerText, required this.evidence});

  final String answerText;
  final List<Evidence> evidence;

  factory RecallAnswer.fromDto(RecallAnswerDto dto) => RecallAnswer(
        answerText: dto.answerText,
        evidence: dto.evidence.map(Evidence.fromDto).toList(),
      );
}
