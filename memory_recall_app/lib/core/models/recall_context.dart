import '../platform/memory_backend_api.dart';

/// The three recall cases (Sections 4.5 / 9).
enum RecallCase { knownPerson, unknownPerson, noPerson }

/// Plain Dart model mirroring [RecallContextDto].
///
/// Keeps the originating [dto] so controllers can pass the exact context back to
/// `getSuggestions` / `answerQuestion` without reconstructing it.
class RecallContext {
  const RecallContext({
    required this.dto,
    this.personId,
    this.personName,
    required this.personKnown,
    required this.personPresent,
    this.locationLabel,
    this.latitude,
    this.longitude,
    this.currentTranscript,
    this.frameThumbPath,
    this.recallCue,
  });

  final RecallContextDto dto;
  final int? personId;
  final String? personName;
  final bool personKnown;
  final bool personPresent;
  final String? locationLabel;
  final double? latitude;
  final double? longitude;
  final String? currentTranscript;
  final String? frameThumbPath;

  /// Case A: one warm sentence to jog the user's memory of this person (§7.1).
  final String? recallCue;

  RecallCase get recallCase {
    if (personKnown) return RecallCase.knownPerson;
    if (personPresent) return RecallCase.unknownPerson;
    return RecallCase.noPerson;
  }

  factory RecallContext.fromDto(RecallContextDto dto) => RecallContext(
        dto: dto,
        personId: dto.personId,
        personName: dto.personName,
        personKnown: dto.personKnown,
        personPresent: dto.personPresent,
        locationLabel: dto.locationLabel,
        latitude: dto.latitude,
        longitude: dto.longitude,
        currentTranscript: dto.currentTranscript,
        frameThumbPath: dto.frameThumbPath,
        recallCue: dto.recallCue,
      );
}
