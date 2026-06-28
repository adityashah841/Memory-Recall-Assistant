// Pigeon API definition — the single source of truth for the Flutter <-> Kotlin
// platform contract (IMPLEMENTATION_PLAN.md Section 11).
//
// Regenerate after editing this file:
//   flutter pub run pigeon --input pigeons/memory_backend.dart
//
// Host = Kotlin (`MemoryBackendChannel` implements the generated `MemoryBackendApi`).
// The Flutter side never sees raw embeddings — only paths, text, and ids.
import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/core/platform/memory_backend_api.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/app/src/main/kotlin/com/synapseriders/memory_recall_app/bridge/MemoryBackendApi.g.kt',
    kotlinOptions: KotlinOptions(
      package: 'com.synapseriders.memory_recall_app.bridge',
    ),
    dartPackageName: 'memory_recall_app',
  ),
)

// ---------------------------------------------------------------------------
// 11.1 DTOs
// ---------------------------------------------------------------------------

/// Current context captured on a Recall press.
class RecallContextDto {
  int? personId; // resolved subject, null if none/unknown
  String? personName;
  bool personKnown; // false => Case B (unknown) or Case C (no person)
  bool personPresent; // distinguishes Case B (true) vs Case C (false)
  String? locationLabel;
  double? latitude;
  double? longitude;
  String? currentTranscript;
  String? frameThumbPath;
}

class SuggestionDto {
  String id;
  String questionText;
}

/// One cited session row backing an answer.
class EvidenceDto {
  int sessionId;
  int timestampMs;
  String? locationLabel;
  String? frameThumbPath;
  String? transcriptSnippet;
}

class RecallAnswerDto {
  String answerText;
  List<EvidenceDto> evidence;
}

class PersonProfileDto {
  int personId;
  String? name;
  String? contextNotes;
}

/// For timeline / debug.
class MemorySessionDto {
  int sessionId;
  int? personId;
  String? personName;
  String? locationLabel;
  int timestampMs;
  String frameImagePath;
  String? transcript;
}

class ServiceStateDto {
  bool running;
  int sessionCount;
  int personCount;
}

/// Result of a proactive 5-frame enrollment (§3.6, CP8).
class EnrollResultDto {
  bool success;
  int personId; // new profile id, or -1 on failure
  int samplesCaptured; // centered frames actually embedded
  String? message;
}

// ---------------------------------------------------------------------------
// 11.2 Host API (Kotlin implements; Flutter calls). All calls are async.
// ---------------------------------------------------------------------------

@HostApi()
abstract class MemoryBackendApi {
  // Service lifecycle (capture loop)
  void startCapture();
  void stopCapture();
  @async
  ServiceStateDto getServiceState();
  void forceTick(); // debug: run one capture tick now

  // Recall flow
  RecallContextDto captureRecallContext(); // identify subject from current frame
  List<SuggestionDto> getSuggestions(RecallContextDto ctx);
  RecallAnswerDto answerQuestion(
    RecallContextDto ctx,
    String suggestionId,
    String? freeText,
  );

  // Enrollment (Case B follow-up): name an existing unknown profile.
  @async
  void enrollPerson(int personId, String name, String? contextNotes);

  // Enrollment (CP8, §3.6): capture 5 centered frames from the live camera, average their
  // embeddings, and store a new named profile. Requires the capture service to be running.
  @async
  EnrollResultDto enrollNewPerson(String name, String? contextNotes);

  // Timeline / debug
  @async
  List<MemorySessionDto> listSessions(int limit, int offset);
  @async
  List<PersonProfileDto> listPeople();
  @async
  void seedDemoData(); // DemoSeeder
  @async
  void clearAllData();
}
