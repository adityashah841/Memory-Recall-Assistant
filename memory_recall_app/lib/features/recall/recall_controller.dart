import 'package:flutter/foundation.dart';

import '../../core/models/recall_answer.dart';
import '../../core/models/recall_context.dart';
import '../../core/platform/memory_backend_channel.dart';

/// Drives the press-to-recall flow (Sections 9–10). Stays UI-state only; all
/// retrieval/ranking/answering lives in Kotlin behind the channel.
class RecallController extends ChangeNotifier {
  RecallController(this._backend);

  final MemoryBackendChannel _backend;

  bool _capturing = false;
  bool _answering = false;
  String? _error;
  RecallContext? _context;
  List<Suggestion> _suggestions = const [];
  Suggestion? _activeSuggestion;
  RecallAnswer? _answer;

  bool get capturing => _capturing;
  bool get answering => _answering;
  String? get error => _error;
  RecallContext? get context => _context;
  List<Suggestion> get suggestions => _suggestions;
  Suggestion? get activeSuggestion => _activeSuggestion;
  RecallAnswer? get answer => _answer;

  /// Capture current context, branch on the case, and fetch suggestions where
  /// history is available (Cases A and C).
  Future<void> recall() async {
    _capturing = true;
    _error = null;
    _context = null;
    _suggestions = const [];
    _activeSuggestion = null;
    _answer = null;
    notifyListeners();

    try {
      final ctxDto = await _backend.captureRecallContext();
      final ctx = RecallContext.fromDto(ctxDto);
      _context = ctx;

      if (ctx.recallCase != RecallCase.unknownPerson) {
        final dtos = await _backend.getSuggestions(ctxDto);
        _suggestions = dtos.map(Suggestion.fromDto).toList();
      }
    } catch (e) {
      _error = e.toString();
    } finally {
      _capturing = false;
      notifyListeners();
    }
  }

  /// Produce and show the grounded answer for a selected suggestion (Section 10.4).
  Future<void> selectSuggestion(Suggestion suggestion) async {
    final ctx = _context;
    if (ctx == null) return;

    _answering = true;
    _activeSuggestion = suggestion;
    _answer = null;
    _error = null;
    notifyListeners();

    try {
      final answerDto =
          await _backend.answerQuestion(ctx.dto, suggestion.id, null);
      _answer = RecallAnswer.fromDto(answerDto);
    } catch (e) {
      _error = e.toString();
    } finally {
      _answering = false;
      notifyListeners();
    }
  }
}
