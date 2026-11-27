import 'package:flutter/services.dart';

import 'memory_backend_api.dart';

/// Thin DI wrapper around the Pigeon-generated [MemoryBackendApi].
///
/// This is the single place the Dart side talks to Kotlin (Section 13.1). It exists so
/// controllers depend on an injectable seam (easy to fake in widget tests) and so error
/// handling lives in one location. It deliberately exposes the generated DTOs directly;
/// controllers map those into plain `core/models` for the widgets.
class MemoryBackendChannel {
  MemoryBackendChannel({MemoryBackendApi? api})
      : _api = api ?? MemoryBackendApi();

  final MemoryBackendApi _api;

  // --- Service lifecycle ---
  Future<void> startCapture() => _guard(_api.startCapture);
  Future<void> stopCapture() => _guard(_api.stopCapture);
  Future<ServiceStateDto> getServiceState() => _guard(_api.getServiceState);
  Future<void> forceTick() => _guard(_api.forceTick);

  // --- Recall flow ---
  Future<RecallContextDto> captureRecallContext() =>
      _guard(_api.captureRecallContext);
  Future<List<SuggestionDto>> getSuggestions(RecallContextDto ctx) =>
      _guard(() => _api.getSuggestions(ctx));
  Future<RecallAnswerDto> answerQuestion(
    RecallContextDto ctx,
    String suggestionId,
    String? freeText,
  ) =>
      _guard(() => _api.answerQuestion(ctx, suggestionId, freeText));

  // --- Live camera view ---
  Future<LivePreviewDto> getLivePreview(int maxWidth) =>
      _guard(() => _api.getLivePreview(maxWidth));

  // --- Enrollment ---
  Future<void> enrollPerson(int personId, String name, String? contextNotes) =>
      _guard(() => _api.enrollPerson(personId, name, contextNotes));

  /// CP8: capture 5 centered frames on-device and store a new named profile.
  Future<EnrollResultDto> enrollNewPerson(String name, String? contextNotes) =>
      _guard(() => _api.enrollNewPerson(name, contextNotes));

  // --- Timeline / debug ---
  Future<List<MemorySessionDto>> listSessions(int limit, int offset) =>
      _guard(() => _api.listSessions(limit, offset));
  Future<List<PersonProfileDto>> listPeople() => _guard(_api.listPeople);
  Future<void> seedDemoData() => _guard(_api.seedDemoData);
  Future<void> clearAllData() => _guard(_api.clearAllData);

  /// Central error funnel: translates platform-channel failures into a typed
  /// [BackendException] so controllers can surface a clean message.
  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on PlatformException catch (e) {
      throw BackendException(e.message ?? 'Platform error', e.code);
    }
  }
}

class BackendException implements Exception {
  BackendException(this.message, [this.code]);
  final String message;
  final String? code;

  @override
  String toString() => 'BackendException(${code ?? '-'}): $message';
}
