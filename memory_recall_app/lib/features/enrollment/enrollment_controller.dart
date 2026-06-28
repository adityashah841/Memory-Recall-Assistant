import 'package:flutter/foundation.dart';

import '../../core/models/person_profile.dart';
import '../../core/platform/memory_backend_channel.dart';

/// Lists known people and names/labels an unknown one (Case B, Section 13.1).
class EnrollmentController extends ChangeNotifier {
  EnrollmentController(this._backend);

  final MemoryBackendChannel _backend;

  bool _loading = false;
  bool _submitting = false;
  String? _error;
  String? _message;
  List<PersonProfile> _people = const [];

  bool get loading => _loading;
  bool get submitting => _submitting;
  String? get error => _error;
  String? get message => _message;
  List<PersonProfile> get people => _people;

  Future<void> load() async {
    _loading = true;
    _error = null;
    notifyListeners();
    try {
      final dtos = await _backend.listPeople();
      _people = dtos.map(PersonProfile.fromDto).toList();
    } catch (e) {
      _error = e.toString();
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  Future<void> enroll(int personId, String name, String? contextNotes) async {
    _submitting = true;
    _error = null;
    _message = null;
    notifyListeners();
    try {
      await _backend.enrollPerson(personId, name, contextNotes);
      _message = 'Saved "$name".';
      await load();
    } catch (e) {
      _error = e.toString();
    } finally {
      _submitting = false;
      notifyListeners();
    }
  }
}
