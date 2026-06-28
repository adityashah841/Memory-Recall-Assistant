import '../platform/memory_backend_api.dart';

/// Plain Dart model mirroring [MemorySessionDto] for timeline/debug widgets.
class MemorySession {
  const MemorySession({
    required this.sessionId,
    this.personId,
    this.personName,
    this.locationLabel,
    required this.timestampMs,
    required this.frameImagePath,
    this.transcript,
  });

  final int sessionId;
  final int? personId;
  final String? personName;
  final String? locationLabel;
  final int timestampMs;
  final String frameImagePath;
  final String? transcript;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  /// Case C rows have no associated person.
  bool get hasPerson => personId != null;
  String get subjectLabel =>
      personName ?? (hasPerson ? 'Unknown #$personId' : 'No person');

  factory MemorySession.fromDto(MemorySessionDto dto) => MemorySession(
        sessionId: dto.sessionId,
        personId: dto.personId,
        personName: dto.personName,
        locationLabel: dto.locationLabel,
        timestampMs: dto.timestampMs,
        frameImagePath: dto.frameImagePath,
        transcript: dto.transcript,
      );
}
