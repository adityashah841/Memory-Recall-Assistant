import 'package:flutter/foundation.dart';

import '../../core/models/memory_session.dart';
import '../../core/platform/memory_backend_channel.dart';

/// Loads a page of stored sessions for the timeline (Section 13.1).
class TimelineController extends ChangeNotifier {
  TimelineController(this._backend);

  final MemoryBackendChannel _backend;

  static const int _pageSize = 50;

  bool _loading = false;
  String? _error;
  List<MemorySession> _sessions = const [];

  bool get loading => _loading;
  String? get error => _error;
  List<MemorySession> get sessions => _sessions;

  Future<void> load() async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final dtos = await _backend.listSessions(_pageSize, 0);
      _sessions = dtos.map(MemorySession.fromDto).toList();
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }
}
