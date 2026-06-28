import 'package:flutter/foundation.dart';

import '../../core/platform/memory_backend_api.dart';
import '../../core/platform/memory_backend_channel.dart';

/// Backs the debug controls (Section 13.1): service toggle, force tick, seed/clear.
class DebugController extends ChangeNotifier {
  DebugController(this._backend);

  final MemoryBackendChannel _backend;

  bool _busy = false;
  String? _error;
  ServiceStateDto? _state;

  bool get busy => _busy;
  String? get error => _error;
  ServiceStateDto? get state => _state;

  Future<void> refresh() => _run(() async {
        _state = await _backend.getServiceState();
      });

  Future<void> startCapture() => _run(() async {
        await _backend.startCapture();
        _state = await _backend.getServiceState();
      });

  Future<void> stopCapture() => _run(() async {
        await _backend.stopCapture();
        _state = await _backend.getServiceState();
      });

  Future<void> forceTick() => _run(() async {
        await _backend.forceTick();
        _state = await _backend.getServiceState();
      });

  Future<void> seedDemoData() => _run(() async {
        await _backend.seedDemoData();
        _state = await _backend.getServiceState();
      });

  Future<void> clearAllData() => _run(() async {
        await _backend.clearAllData();
        _state = await _backend.getServiceState();
      });

  Future<void> _run(Future<void> Function() action) async {
    _busy = true;
    _error = null;
    notifyListeners();
    try {
      await action();
    } catch (e) {
      _error = e.toString();
    } finally {
      _busy = false;
      notifyListeners();
    }
  }
}
