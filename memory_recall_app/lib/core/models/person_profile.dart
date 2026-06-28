import '../platform/memory_backend_api.dart';

/// Plain Dart model mirroring [PersonProfileDto] for use in widgets (Section 13.1).
class PersonProfile {
  const PersonProfile({
    required this.personId,
    this.name,
    this.referenceFaceImagePath,
    this.contextNotes,
  });

  final int personId;
  final String? name;
  final String? referenceFaceImagePath;
  final String? contextNotes;

  bool get isNamed => name != null && name!.isNotEmpty;
  String get displayName => isNamed ? name! : 'Unknown #$personId';

  factory PersonProfile.fromDto(PersonProfileDto dto) => PersonProfile(
        personId: dto.personId,
        name: dto.name,
        referenceFaceImagePath: dto.referenceFaceImagePath,
        contextNotes: dto.contextNotes,
      );
}
