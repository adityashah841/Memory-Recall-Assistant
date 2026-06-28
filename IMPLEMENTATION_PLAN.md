# Memory Recall Assistant — Implementation Plan (v2.0, Landmark Identity)

> **Status:** Authoritative build specification for all sub-agents and teammates.
> **Device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite for Galaxy, 12 GB RAM).
> **Framework:** Flutter (UX) + Kotlin (Android native), bridged by **Pigeon**.
> **Golden rule:** Schemas (§5), insertion/querying flows (§9–§10), the recall pipeline (§11), and
> the Flutter↔Kotlin API (§13) must stay mutually consistent. A change to one requires a matching
> change to the others. If code and this document disagree, fix the code — do not silently diverge.
> **Privacy invariant (hard):** **No face images and no face embeddings are ever stored, anywhere,
> at any point.** Identity is geometric landmark math only.

---

## 0. Current status & incremental path

This project is **already underway**. The data layer has been migrated to the v2.0 **privacy-first
landmark geometry** schema (all embeddings removed). The next task is **Checkpoint 3** (the MediaPipe
ExecuTorch runner).

| Checkpoint | State | Notes |
| --- | --- | --- |
| **CP1 — Skeleton & Pigeon bridge** | ✅ Done | Flutter shell (4 screens), Pigeon API, `MemoryBackendChannel` with canned data, `MainActivity` host registration. |
| **CP2 — Data layer** | ✅ Done (superseded) | Original Room DB built on the v1 embeddings schema. |
| **CP2.5 — Schema migration to landmark identity** | ✅ Done | Data layer migrated to the v2 schema (§5): `landmark_vector` only, no embeddings; Pigeon DTOs updated (§13). |
| **CP3 → CP10** | ⛔ Not started | Proceed in order per §20. **CP3 is next.** |

**CP3 is next.** Everything downstream (identity, capture, recall) assumes the v2 schema, which is now in place.

---

## 1. Model stack (final, locked)

All model files live under `android/app/src/main/assets/models/`.

| Purpose | File | Format | Runtime / Quantization |
| --- | --- | --- | --- |
| Face detection | `mediapipe_face_detection.pte` | ExecuTorch | QNN/NPU — float |
| Face landmark extraction | `mediapipe_face_landmark_detector.pte` | ExecuTorch | QNN/NPU — float |
| ASR encoder | `whisper_encoder.bin` | **QNN context binary** | QNN/NPU — **w8a16** |
| ASR decoder | `whisper_decoder.bin` | **QNN context binary** | QNN/NPU — **w8a16** |
| LLM (cue + answer generation) | `llama3_2_1b_instruct.pte` | ExecuTorch | QNN/NPU — **w4a16** |
| ASR metadata / tensor specs | `metadata.json` | JSON | Reference only |

> **No MobileFaceNet. No MobileCLIP. No face embeddings stored anywhere. No face images stored
> anywhere.** Whisper is the **only** model that is **not** ExecuTorch — it loads via the QNN SDK
> context-binary API.

---

## 2. Identity architecture (privacy-first, landmark-based)

### 2.1 What changed from the original (v1) plan
- ❌ REMOVED: MobileFaceNet face embeddings.
- ❌ REMOVED: MobileCLIP image/text embeddings (`frame_embedding`, `voice_embedding`).
- ❌ REMOVED: `reference_face_image` column and any face-image storage at any point.
- ❌ REMOVED: `voice_id` column and vector similarity search.
- ✅ ADDED: Normalized **landmark geometry** replaces face embeddings.
- ✅ ADDED: Dot-product matching on `float32[936]` landmark vectors.

### 2.2 Identity pipeline
```text
Camera frame
      ↓
[mediapipe_face_detection.pte]            →  bounding box
      ↓
Crop face region (reject if < MIN_FACE_SIZE_PX)
      ↓
[mediapipe_face_landmark_detector.pte]    →  468 (x,y) landmark points (float32[936] raw)
      ↓
LandmarkIdentityEngine.normalize():
   1. Compute centroid (mean x, mean y)
   2. Subtract centroid from all 468 points          (translation invariance)
   3. Compute inter-ocular distance (eye-center to eye-center)
   4. Divide all points by inter-ocular distance      (scale invariance)
   5. Rotate so the eye line is horizontal             (rotation invariance)
   6. Flatten to float32[936]
   7. L2-normalize                                     (so dot product = cosine)
      ↓
Dot product vs every stored landmark_vector in person_profile_table
      ↓
Score >= TAU_KNOWN (0.92)  →  Known person   (Case A)
Score <  TAU_KNOWN         →  Unknown person (Case B)
No face detected           →  No person      (Case C)
```

### 2.3 Enrollment
- Capture **5 frames** of the same person.
- Extract + normalize a landmark vector from each frame.
- Average the 5 vectors, then **L2-normalize the average**.
- Store in `person_profile_table.landmark_vector`.
- **No face image is ever saved at any point.**

### 2.4 Identity matching constants (single source of truth)
```kotlin
const val TAU_KNOWN = 0.92f          // known/unknown threshold; tune after on-device testing
const val MIN_FACE_SIZE_PX = 80      // ignore small/distant faces
const val ENROLLMENT_SAMPLES = 5     // frames averaged per enrollment
const val VICINITY_RADIUS_M = 75.0   // location vicinity for retrieval
```
These live in **one** centralized Kotlin object (e.g. `identity/IdentityConfig.kt`); never duplicate
the numbers.

---

## 3. Data schema

> Storage engine: **Room over SQLite**. The only stored vector is `landmark_vector`
> (`float32[936]`, L2-normalized at write time so similarity = dot product). Retrieval is **SQL-based**
> (`person_id` + location + recency) — **no ANN index, no vector search** at hackathon scale.

### 3.1 `person_profile_table` (entity: `PersonProfileEntity`)
| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `person_id` | INTEGER PK autoincrement | No | Auto-assigned as new people are encountered. |
| `name` | TEXT | Yes | Filled at enrollment. |
| `landmark_vector` | BLOB `float32[936]` | No | L2-normalized geometric identity — **NOT a face embedding**. |
| `context_notes` | TEXT | Yes | LLM-inferred or user-provided. |
| `created_at` | INTEGER epoch ms | No | |
| `updated_at` | INTEGER epoch ms | No | |

### 3.2 `session_table` (entity: `MemorySessionEntity`)
| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `session_id` | INTEGER PK autoincrement | No | |
| `person_id` | INTEGER FK → `person_profile_table` | **Yes** | `NULL` when no person in frame (Case C). `ON DELETE SET NULL`. |
| `location_label` | TEXT | Yes | Room or place label. |
| `latitude` | REAL | Yes | Fused location. |
| `longitude` | REAL | Yes | Fused location. |
| `timestamp` | INTEGER epoch ms | No | Event time. |
| `frame_image` | TEXT path | No | Compressed JPEG **scene** thumbnail (not a stored face crop). |
| `voice_clip` | TEXT path | Yes | Audio clip for the window. |
| `transcript` | TEXT | Yes | Whisper ASR output. |
| `created_at` | INTEGER epoch ms | No | |

> **No `frame_embedding` or `voice_embedding` columns.** Retrieval is SQL (`person_id` + location +
> recency), not vector-based. This simplifies the pipeline significantly.

### 3.3 Indices
```sql
CREATE INDEX idx_session_person_id ON session_table(person_id);
CREATE INDEX idx_session_timestamp ON session_table(timestamp);
CREATE INDEX idx_session_location  ON session_table(latitude, longitude);
```

---

## 4. ASR pipeline (Whisper via QNN)

> **Critical:** Whisper uses the **QNN context-binary** format (`.bin`), **NOT** ExecuTorch. Load via
> the QNN SDK context-binary API. All other models use ExecuTorch `Module.load()`.

```text
Microphone audio (16 kHz, mono, PCM16)
      ↓
Rolling 30-second window buffer (TranscriptBuffer)
      ↓
Log-mel spectrogram: float32[1, 80, 3000]
      ↓
[whisper_encoder.bin] via QNN SDK  →  audio features
      ↓
[whisper_decoder.bin] via QNN SDK  →  token ids (max 200)
      ↓
Tokenizer decode  →  transcript text
      ↓
Store in session_table.transcript
```

**Input specs (from `metadata.json`):**
- Encoder input: `float32[1, 80, 3000]` — 30 seconds of audio.
- Decoder input: `int32` token sequence, max 200 tokens.
- Quantization: **w8a16** (8-bit weights, 16-bit activations).
- **Fallback:** Android `SpeechRecognizer` if the QNN binary fails to load — **never crash**.

---

## 5. LLM pipeline (Llama 3.2 1B via ExecuTorch)

Used for two purposes: (1) generating warm natural recall cues, (2) synthesizing answers from
retrieved transcripts.

### 5.1 Recall-cue prompt template
```text
<|begin_of_text|><|start_header_id|>system<|end_header_id|>
You are a private memory assistant. Generate one short warm
sentence to help the user remember the person in front of them.
Never mention memory loss. Maximum 2 sentences.
<|eot_id|><|start_header_id|>user<|end_header_id|>
Person: {name}
Last seen: {last_seen_timestamp}
Location: {location_label}
Recent conversation: {transcript_snippet}
Generate the recall cue:
<|eot_id|><|start_header_id|>assistant<|end_header_id|>
```

### 5.2 Answer-generation prompt template
```text
<|begin_of_text|><|start_header_id|>system<|end_header_id|>
You are a private memory assistant. Answer the question using only
the provided memory records. Cite specific sessions. If no evidence
exists, say "I don't have a memory of that."
<|eot_id|><|start_header_id|>user<|end_header_id|>
Question: {selected_question}
Memory records:
{session_1_transcript}
{session_2_transcript}
{session_3_transcript}
Answer with evidence:
<|eot_id|><|start_header_id|>assistant<|end_header_id|>
```

### 5.3 LLM settings
- Recall cue: `max_tokens=60`, `temperature=0.3`.
- Answer generation: `max_tokens=200`, `temperature=0.3`.
- Stop tokens: `["\n\n", "<|eot_id|>"]`.
- Template fallback if generation times out (>5 s): `"This is {name}."`
- Quantization: **w4a16** (4-bit weights, 16-bit activations).

---

## 6. Architecture overview

### 6.1 Flutter / Kotlin split
```text
+----------------------------------------------------------+
|                    FLUTTER (Dart)                        |
|  RecallScreen  TimelineScreen  EnrollmentScreen          |
|  DebugScreen                                             |
|  All calls via Pigeon typed platform channel             |
+-------------------------+--------------------------------+
                          | Pigeon
                          ↓
+----------------------------------------------------------+
|                    KOTLIN (Android)                      |
|                                                          |
| bridge/      MemoryBackendChannel (Pigeon host)          |
|              DtoMappers                                  |
|                                                          |
| service/     MemoryForegroundService                     |
|              CaptureLoop (periodic tick ~5s)             |
|                                                          |
| capture/     CameraFrameProvider (CameraX)               |
|              TranscriptBuffer (rolling 30s ASR window)   |
|              LocationProvider (fused + manual label)     |
|              DemoContextProvider (scripted demo input)   |
|                                                          |
| ai/          OnDeviceModelRunner (interface)             |
|              ExecuTorchModelRunner (MediaPipe + Llama)   |
|              WhisperQnnRunner (encoder.bin + decoder.bin)|
|              FakeModelRunner (tests/CI only)             |
|                                                          |
| identity/    LandmarkIdentityEngine                      |
|              IdentityConfig (TAU_KNOWN, etc.)            |
|              (normalization + dot product — pure Kotlin) |
|                                                          |
| recall/      RecallEngine                                |
|              RetrievalRanker (SQL + recency)             |
|              QuestionSuggestionEngine                    |
|              AnswerGenerator                             |
|                                                          |
| data/        MemoryDatabase (Room)                       |
|              PersonProfileEntity / MemorySessionEntity   |
|              PersonDao / SessionDao                      |
|              MemoryRepository (single entry point)       |
|              LandmarkCodec (float32[]↔blob + L2 norm)    |
|              DemoSeeder                                  |
|                                                          |
| enrollment/  PersonEnrollmentManager                     |
+----------------------------------------------------------+
```

### 6.2 AI module split
| Class | Models | Runtime |
| --- | --- | --- |
| `ExecuTorchModelRunner` | `mediapipe_face_detection.pte`, `mediapipe_face_landmark_detector.pte`, `llama3_2_1b_instruct.pte` | ExecuTorch AAR |
| `WhisperQnnRunner` | `whisper_encoder.bin`, `whisper_decoder.bin` | QNN SDK |
| `LandmarkIdentityEngine` | None — pure Kotlin math | N/A |
| `FakeModelRunner` | Deterministic stubs | Tests/CI only — **never** in demo build |

### 6.3 `OnDeviceModelRunner` interface (Kotlin)
```kotlin
interface OnDeviceModelRunner {
    fun detectFaces(frame: Bitmap): List<FaceBox>
    fun extractLandmarks(faceCrop: Bitmap): FloatArray   // float32[936] raw landmark points
    fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String
    fun generate(prompt: String, maxTokens: Int = 256): String
    fun warmUp()                                         // preload/compile models off UI thread
}
```
> Note: `WhisperQnnRunner` may implement only `transcribe`/`warmUp`, while `ExecuTorchModelRunner`
> implements detect/landmarks/generate; a composite runner can delegate. There are **no**
> `embedFace`/`embedImage`/`embedText` methods — landmark extraction replaces all embedding calls.

### 6.4 Data flow
```text
WRITE PATH (Capture):
CaptureLoop → capture/* providers → ExecuTorchModelRunner (detect + landmarks)
           → LandmarkIdentityEngine (identify) → decision (Case A/B/C)
           → MemoryRepository → Room DB

READ PATH (Recall):
Flutter RecallScreen → Pigeon → RecallEngine
  → LandmarkIdentityEngine (identify current frame)
  → MemoryRepository (SQL retrieval by person_id + location)
  → RetrievalRanker (recency sort)
  → QuestionSuggestionEngine (templates + optional LLM rewrite)
  → AnswerGenerator (LLM grounded in retrieved sessions)
  → Pigeon → Flutter
```

---

## 7. Core working — camera, knowledge graph, identification

This section reproduces the **core working** requirements. Sub-agents must not deviate.

### 7.1 Camera is always live, captured periodically
The camera is **always live** while `MemoryForegroundService` runs, capturing in a **periodic**
fashion via `CaptureLoop`. A **tick** fires on cadence (default **~5 s**, configurable) and/or on an
event trigger (face detected / significant scene change). A tick may **skip** persistence when
nothing meaningful is present (§9.4) — this is what keeps the app from being an always-on recorder.

### 7.2 What is available at a tick
- **One or more frames** from the camera.
- A frame **may or may not contain a person**. If a person is identified → `person_id`; otherwise →
  **unknown** / none.
- **Conversation / voice of the last ~30 s** (rolling `TranscriptBuffer`).
- **Location** (fused location + manual room/location label).

### 7.3 Knowledge graph = two tables
The knowledge graph is exactly the two tables in §5: `person_profile_table` (the **landmark identity
index**) and `session_table` (time-stamped events: who (nullable) + where + frame + voice/transcript).

### 7.4 Identifying a person
Detect the face in the current frame, extract + normalize landmarks, and **dot-product match against
every `person_profile_table.landmark_vector`** (§2.2). `score >= TAU_KNOWN` ⇒ known; else ⇒ unknown;
no face ⇒ no person.

---

## 8. Person identification algorithm (detail)

Given the current frame (at a capture tick or at recall time):
1. **Detect** faces (`mediapipe_face_detection.pte`). No face ⇒ **Case C** (no person).
2. Select the **primary subject**: largest bbox; reject faces smaller than `MIN_FACE_SIZE_PX`.
3. **Crop** the primary face; `raw = extractLandmarks(crop)` (`float32[936]`).
4. `q = LandmarkIdentityEngine.normalize(raw)` (centroid-subtract → inter-ocular scale → eye-line
   rotate → L2-normalize).
5. For each profile `p_i`: `score_i = dot(q, p_i.landmark_vector)`. Let `best = argmax_i score_i`.
   - `score_best >= TAU_KNOWN` ⇒ **Known** (`person_id = best`) → Case A.
   - else ⇒ a person is present but unmatched ⇒ **Unknown/new** → Case B.
6. Identity uses **only** `landmark_vector`. The face crop is discarded immediately after step 3 —
   it is **never** stored.

---

## 9. Insertion flows (write path — three cases)

`CaptureLoop` tick gathers: primary frame, detected person (or none), last ~30 s transcript + audio
clip, and location. Then it runs §8 and branches:

### 9.A Known person (`score >= TAU_KNOWN`)
- **No change** to `person_profile_table`.
- **Insert one row** into `session_table` with `person_id = matched_id` (location, timestamp, scene
  `frame_image`, `voice_clip`, `transcript`).

### 9.B Unknown / new person (present, not matched)
- **Insert a new profile** into `person_profile_table` with `name = NULL`,
  `landmark_vector = normalized landmarks (q)`, `context_notes = LLM-inferred-if-any`.
- **Insert a new session** into `session_table` with the **new `person_id`**.

### 9.C No person in frame
- **Insert directly** into `session_table` with **`person_id = NULL`**.

### 9.4 Skip conditions (avoid always-on recording)
A tick **does not persist** unless at least one holds:
- a person is present, **or**
- there is a non-empty transcript, **or**
- the scene changed meaningfully vs. the last stored frame.

---

## 10. Querying / recall flows (read path — three cases)

Triggered by the **Recall** button. The app captures the *current* context (frame → primary person
via §8, current location, optional current transcript) and branches:

### 10.A Known person
- **SQL-filter** `session_table` by `person_id = matched_id` **and** location in **vicinity**
  (same `location_label` **or** within `VICINITY_RADIUS_M` of the current lat/lng).
- Sort by `timestamp DESC`, take **top 5**.
- Build suggested questions (§11.3); on selection, **immediately** produce the answer with evidence
  (§11.4).

### 10.B Unknown / new person
- Current subject does **not** match any profile ⇒ **inform the user this person is unknown** and
  offer to enroll/name them via `EnrollmentScreen`. No history is available yet.

### 10.C No person in frame
- **SQL-filter** `session_table` by `person_id IS NULL` **and** location in **vicinity**, sort by
  `timestamp DESC`, take **top 5** → suggested questions → answer with evidence.
- (No vector search — retrieval is SQL + recency per §3.)

---

## 11. Recall pipeline (retrieval → rank → suggest → answer)

```text
current context → identify subject (§8) → branch (Case A/B/C)
      │
      ├── RetrievalRanker.retrieve(filter)       # SQL candidate rows (person_id + vicinity)
      ├── RetrievalRanker.rank(candidates)       # recency sort (timestamp DESC), top 5
      ├── QuestionSuggestionEngine.suggest(top)  # template-first, optional LLM rewrite
      └── AnswerGenerator.answer(selected, top)  # LLM grounded answer + evidence
```

### 11.1 Query construction
`query_text` is built from the current context (person name/label + location + current transcript) or
from a user-selected suggested question. **No embedding** is computed — retrieval is SQL.

### 11.2 Retrieval & vicinity
- SQL filter: case filter (`person_id = X` / `person_id IS NULL`) **AND** vicinity (same
  `location_label` **or** within `VICINITY_RADIUS_M`). Missing location ⇒ fall back to the
  person-only filter.
- Sort by `timestamp DESC`, return **top 5**.

### 11.3 Question suggestions (template-first)
Templates over the retrieved rows; the LLM may **rewrite** for natural phrasing but must not invent
topics absent from the rows:
- `"What did {name} say about {topic} at {place}?"`
- `"When did I last see {name}?"`
- `"What did we discuss at {location_label}?"`

### 11.4 Answer + evidence
`AnswerGenerator` calls the LLM (§5.2) grounded in the top session transcripts and returns a
`RecallAnswerDto` whose `evidence` lists the **cited** session rows (session id, timestamp, location,
optional thumbnail path, transcript snippet). No evidence ⇒ `"I don't have a memory of that."`

---

## 12. Threading rules (non-negotiable)
- All model inference runs on `Dispatchers.Default` — never the main thread.
- All Room queries run on `Dispatchers.IO` — never the main thread.
- All Pigeon host methods run on background dispatchers.
- UI updates only via `StateFlow` / `LiveData` collected on the main thread.
- Model **warm-up** happens once at service start — never per-inference.

---

## 13. Flutter ↔ Kotlin API contract (Pigeon)

Defined in `pigeons/memory_backend.dart`. Regenerate after any change:
```bash
flutter pub run pigeon --input pigeons/memory_backend.dart
```

### 13.1 DTOs
```dart
class RecallContextDto {
  int? personId;
  String? personName;
  bool personKnown;     // false => Case B (unknown) or Case C (no person)
  bool personPresent;   // distinguishes Case B (true) vs Case C (false)
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

class PersonProfileDto {   // NOTE: no referenceFaceImagePath — no face images exist
  int personId;
  String? name;
  String? contextNotes;
}

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
```

### 13.2 Host API (Kotlin implements; Flutter calls)
```dart
@HostApi()
abstract class MemoryBackendApi {
  // Service lifecycle
  void startCapture();
  void stopCapture();
  ServiceStateDto getServiceState();
  void forceTick();                    // debug: run one capture tick now

  // Recall flow
  RecallContextDto captureRecallContext();
  List<SuggestionDto> getSuggestions(RecallContextDto ctx);
  RecallAnswerDto answerQuestion(RecallContextDto ctx, String suggestionId, String? freeText);

  // Enrollment
  void enrollPerson(int personId, String name, String? contextNotes);

  // Timeline / debug
  List<MemorySessionDto> listSessions(int limit, int offset);
  List<PersonProfileDto> listPeople();
  void seedDemoData();
  void clearAllData();
}
```
The Flutter side never sees `landmark_vector` — only ids, paths, and text. Mapping lives in
`bridge/DtoMappers.kt`.

---

## 14. Repository structure

```text
memory_recall_app/
  pubspec.yaml
  pigeons/
    memory_backend.dart                      # Pigeon API definition (source)
  lib/
    main.dart
    app.dart
    core/
      models/
        recall_context.dart
        memory_session.dart
        person_profile.dart
        recall_answer.dart
      platform/
        memory_backend_api.dart              # generated by Pigeon
        memory_backend_channel.dart
      theme/
    features/
      recall/
        recall_screen.dart
        recall_controller.dart
        widgets/
      timeline/
        timeline_screen.dart
        timeline_controller.dart
      enrollment/
        enrollment_screen.dart
        enrollment_controller.dart
      debug/
        debug_screen.dart
        debug_controller.dart
  android/
    app/src/main/kotlin/<pkg>/
      MainActivity.kt
      bridge/
        MemoryBackendChannel.kt              # implements generated Pigeon host API
        DtoMappers.kt
      service/
        MemoryForegroundService.kt
        CaptureLoop.kt
      capture/
        CameraFrameProvider.kt               # CameraX
        TranscriptBuffer.kt                  # rolling 30s ASR window
        LocationProvider.kt                  # fused + manual label
        DemoContextProvider.kt               # scripted demo input
      data/
        MemoryDatabase.kt
        PersonProfileEntity.kt
        MemorySessionEntity.kt
        PersonDao.kt
        SessionDao.kt
        MemoryRepository.kt                  # single entry point
        LandmarkCodec.kt                     # float32[]↔blob + L2 norm (was EmbeddingCodec)
        DemoSeeder.kt
      recall/
        RecallEngine.kt
        RetrievalRanker.kt                   # SQL + recency
        QuestionSuggestionEngine.kt
        AnswerGenerator.kt
      ai/
        OnDeviceModelRunner.kt               # interface (§6.3)
        ExecuTorchModelRunner.kt             # MediaPipe + Llama
        WhisperQnnRunner.kt                  # QNN context binary
        FakeModelRunner.kt                   # tests/CI only
      identity/
        LandmarkIdentityEngine.kt            # normalization + dot product (pure Kotlin)
        IdentityConfig.kt                    # TAU_KNOWN, MIN_FACE_SIZE_PX, etc.
      enrollment/
        PersonEnrollmentManager.kt
    app/src/main/assets/models/              # all model files (§15)
    app/libs/                                # executorch-android-release.aar
```

---

## 15. File placement

```text
android/app/src/main/assets/models/
├── mediapipe_face_detection.pte          ← ExecuTorch, QNN/NPU
├── mediapipe_face_landmark_detector.pte  ← ExecuTorch, QNN/NPU
├── whisper_encoder.bin                   ← QNN context binary, w8a16
├── whisper_decoder.bin                   ← QNN context binary, w8a16
├── metadata.json                         ← Whisper input/output tensor specs
└── llama3_2_1b_instruct.pte              ← ExecuTorch, w4a16

android/app/libs/
└── executorch-android-release.aar        ← from github.com/pytorch/executorch/releases
```

---

## 16. Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Flutter requests runtime permissions before `startCapture()`. Kotlin re-checks and fails safely if
any are missing. The foreground service declares types `camera|microphone|location`.

---

## 17. Module responsibilities (per-file)

### 17.1 Flutter
- **`main.dart` / `app.dart`** — bootstrap, theming, routing across the four screens; request runtime
  permissions; obtain the `MemoryBackendApi` instance.
- **`core/models/*`** — plain Dart models mirroring the Pigeon DTOs.
- **`core/platform/memory_backend_api.dart`** — Pigeon-generated bindings (do not hand-edit).
- **`core/platform/memory_backend_channel.dart`** — thin wrapper/DI; central error handling.
- **`features/recall/*`** — press-to-recall UI; renders Case A (name + suggestions + answers +
  evidence), Case B (unknown → enroll prompt), Case C (location-based suggestions).
- **`features/timeline/*`** — paged list of `MemorySessionDto` via `listSessions`.
- **`features/enrollment/*`** — capture 5 frames + name/label an unknown person.
- **`features/debug/*`** — `startCapture`/`stopCapture`/`forceTick`/`seedDemoData`/`clearAllData`,
  service state, and the latency dashboard (§20 CP10).

### 17.2 Kotlin — bridge & service
- **`MainActivity.kt`** — Flutter activity; registers the Pigeon host.
- **`bridge/MemoryBackendChannel.kt`** — implements the generated host API; pure delegation +
  mapping, no business logic.
- **`bridge/DtoMappers.kt`** — Room entity ⇄ Pigeon DTO; never leaks `landmark_vector`.
- **`service/MemoryForegroundService.kt`** — foreground service (camera|mic|location); persistent
  notification; owns `CaptureLoop` lifecycle; warms models once at start.
- **`service/CaptureLoop.kt`** — periodic tick (§7.1); orchestrates providers → identity → §9
  decision → `MemoryRepository`; enforces skip conditions (§9.4).

### 17.3 Kotlin — capture
- **`capture/CameraFrameProvider.kt`** — CameraX; latest frame(s) as `Bitmap`.
- **`capture/TranscriptBuffer.kt`** — rolling 30 s audio window; triggers ASR per tick; exposes
  `getTranscriptWindow()`.
- **`capture/LocationProvider.kt`** — fused location + manual room/label mapping.
- **`capture/DemoContextProvider.kt`** — scripted frames/transcripts/locations for a reliable demo.

### 17.4 Kotlin — ai & identity
- **`ai/OnDeviceModelRunner.kt`** — interface (§6.3).
- **`ai/ExecuTorchModelRunner.kt`** — MediaPipe detect + landmarks + Llama generate; warm-up off UI.
- **`ai/WhisperQnnRunner.kt`** — QNN context-binary ASR; `SpeechRecognizer` fallback.
- **`ai/FakeModelRunner.kt`** — deterministic stubs for tests/CI only.
- **`identity/LandmarkIdentityEngine.kt`** — `normalize()` + `match()` (pure Kotlin math).
- **`identity/IdentityConfig.kt`** — centralized constants (§2.4).

### 17.5 Kotlin — recall, data, enrollment
- **`recall/RecallEngine.kt`** — orchestrates §10–§11 and Case A/B/C branching.
- **`recall/RetrievalRanker.kt`** — SQL candidate narrowing + recency sort (top 5).
- **`recall/QuestionSuggestionEngine.kt`** — template-first suggestions + optional LLM rewrite.
- **`recall/AnswerGenerator.kt`** — grounded answer + evidence assembly.
- **`data/MemoryDatabase.kt`** — Room DB + migrations.
- **`data/PersonProfileEntity.kt` / `MemorySessionEntity.kt`** — exact schema (§5).
- **`data/PersonDao.kt`** — insert, findAll (for landmark scan), findById, update name/notes.
- **`data/SessionDao.kt`** — insert, findByPersonId, findByLocation/vicinity, findRecent, listPaged.
- **`data/MemoryRepository.kt`** — single entry point; owns §9 insertion logic for Cases A/B/C.
- **`data/LandmarkCodec.kt`** — `float32[]` ⇄ little-endian blob + L2-normalize.
- **`data/DemoSeeder.kt`** — coherent demo graph (people with `landmark_vector` + sessions with
  transcripts).
- **`enrollment/PersonEnrollmentManager.kt`** — capture 5 samples → average → L2-normalize → store;
  set name/context on a profile.

---

## 18. What NOT to build (scope boundaries)

| | Item |
| --- | --- |
| ❌ | Any face image storage |
| ❌ | Any face embedding storage (no MobileFaceNet) |
| ❌ | MobileCLIP or any scene/text embedding model |
| ❌ | Vector similarity search (use SQL + recency instead) |
| ❌ | Cloud inference of any kind |
| ❌ | Network calls at runtime (airplane mode must always work) |
| ❌ | Multi-person frame handling (MVP: primary subject only) |
| ❌ | Speaker diarization or voiceprints |
| ❌ | ANN index (linear scan is fine at hackathon scale) |
| ❌ | ExecuTorch runtime for Whisper (use QNN binary directly) |
| ❌ | `FakeModelRunner` in the demo build (tests/CI only) |

---

## 19. Checkpoint 2.5 — data-layer migration (v1 → v2)

CP2 was built on the v1 embeddings schema. Before any new feature work, migrate the existing data
layer to the v2 landmark schema. **This is the immediate next task.**

### 19.1 Exact file-by-file changes
1. **`data/PersonProfileEntity.kt`**
   - Remove columns: `reference_face_image`, `face_embedding`, `voice_id`.
   - Add column: `landmark_vector` (`BLOB`, `float32[936]`, non-null).
   - Update `equals`/`hashCode` accordingly (`landmark_vector` uses `contentEquals`/`contentHashCode`).
2. **`data/MemorySessionEntity.kt`**
   - Remove columns: `frame_embedding`, `voice_embedding`.
   - Keep everything else (incl. FK `ON DELETE SET NULL` and the three indices in §3.3).
3. **`data/EmbeddingCodec.kt` → `data/LandmarkCodec.kt`**
   - Rename/repurpose to a generic `float32[]` ⇄ blob codec with L2-normalize (it already is one).
     Used for `landmark_vector` only.
4. **`data/PersonDao.kt`** — ensure a `findAll()` exists that returns all profiles for the in-memory
   landmark scan; drop any embedding-specific queries.
5. **`data/SessionDao.kt`** — remove embedding columns from inserts/selects; add/confirm
   `findByPersonId`, vicinity query (`person_id` + lat/lng bounding box or `location_label`),
   `findRecent`, `listPaged`. No vector queries.
6. **`data/DemoSeeder.kt`** — seed `landmark_vector` for each demo person (deterministic synthetic
   normalized vectors are fine); stop seeding any frame/voice embeddings.
7. **`bridge/DtoMappers.kt`** — drop `referenceFaceImagePath` mapping; never map `landmark_vector`.
8. **`pigeons/memory_backend.dart`** — remove `referenceFaceImagePath` from `PersonProfileDto`;
   regenerate Dart + Kotlin bindings.
9. **`bridge/MemoryBackendChannel.kt` / `MainActivity.kt`** — update any canned data / wiring that
   referenced removed fields so it compiles.
10. **`data/MemoryDatabase.kt`** — bump the Room schema version; for the hackathon use
    `fallbackToDestructiveMigration()` (no real user data to preserve).

### 19.2 Definition of done
`seedDemoData()` populates the **new** schema; `TimelineScreen` shows rows; `clearAllData()` wipes;
the project compiles; **no embedding columns remain** except `landmark_vector`; Pigeon round-trip
still works (CP1 behavior preserved).

---

## 20. Build checkpoints

Work sequentially. Each checkpoint is independently demoable. Verify the definition of done before
proceeding. (CP1 ✅, CP2 ✅, CP2.5 ✅ → **CP3 is next**, then CP4 onward.)

### CHECKPOINT 1 — Project skeleton & Pigeon bridge — ✅ DONE
Flutter shell (4 screens) + Pigeon API + `MemoryBackendChannel` (canned data) + `MainActivity` host.
**DoD (met):** `RecallScreen` calls `captureRecallContext()` and gets a hardcoded DTO; app runs.

### CHECKPOINT 2 — Data layer — ✅ DONE (superseded by CP2.5)
Original Room DB + entities + DAOs + `MemoryRepository` + `DemoSeeder` + `DtoMappers`, built on the
v1 embeddings schema.

### CHECKPOINT 2.5 — Schema migration to landmark identity — ✅ DONE
Applied §19: data layer migrated to the v2 landmark schema (`landmark_vector` only, no embeddings);
Pigeon DTOs updated. **DoD met** per §19.2.

### CHECKPOINT 3 — ExecuTorch model runner (MediaPipe) — ⛔ NEXT
- ExecuTorch AAR in `android/app/libs/`.
- `ExecuTorchModelRunner.detectFaces()` loads `mediapipe_face_detection.pte` → `List<FaceBox>`.
- `ExecuTorchModelRunner.extractLandmarks()` loads `mediapipe_face_landmark_detector.pte` →
  `float32[936]`.
- Warm up off the UI thread on service start; log per-inference latency (`face_detection`,
  `landmark_extraction`). On load failure: log + return empty — **never crash**.
- **DoD:** Point camera at a face → Logcat shows bbox + 936 landmark values within ~100 ms; no crash
  on model-load failure.

### CHECKPOINT 4 — Landmark identity engine
- `LandmarkIdentityEngine.normalize(raw): FloatArray` (centroid-subtract → inter-ocular scale →
  eye-line rotate → L2-normalize → `float32[936]`).
- `LandmarkIdentityEngine.match(query, profiles): MatchResult` (dot product vs all stored vectors →
  best match + score).
- `TAU_KNOWN = 0.92f` in `IdentityConfig` (single source).
- `PersonEnrollmentManager`: capture 5 samples → average → L2-normalize → store in
  `person_profile_table`.
- Unit tests for `normalize()` verifying **scale** and **translation** invariance.
- **DoD:** Enroll a teammate; point camera at them → correct name with score ≥ 0.92; point at someone
  else → score < 0.92 → Unknown. **Zero face images in DB.**

### CHECKPOINT 5 — Whisper ASR runner (QNN)
- `WhisperQnnRunner` loads `whisper_encoder.bin` + `whisper_decoder.bin` via QNN context-binary API.
- Audio preprocessing: 16 kHz mono PCM16 → log-mel `float32[1, 80, 3000]`.
- `WhisperQnnRunner.transcribe(pcmAudio): String`.
- `TranscriptBuffer`: rolling 30 s window; triggers transcription per `CaptureLoop` tick; exposes
  `getTranscriptWindow()`.
- Track encoder/decoder latency separately. Fallback: `SpeechRecognizer` if the binary fails — never
  crash.
- **DoD:** Speak near the phone → correct transcript in Logcat within ~2 s; fallback activates cleanly
  if `.bin` files fail to load.

### CHECKPOINT 6 — Capture loop (full write path)
- `MemoryForegroundService` starts `CaptureLoop` (foreground camera + mic + location).
- Tick every ~5 s: grab frame → detect + landmarks + identify → get transcript → get location →
  apply skip conditions (§9.4) → insert session per Case A/B/C (§9).
- `forceTick()` callable from `DebugScreen` via Pigeon.
- **DoD:** Service runs in background; after 60 s the DB has multiple session rows; Cases A/B/C all
  produce correct DB state; `forceTick()` runs one tick immediately; CPU/battery reasonable between
  ticks.

### CHECKPOINT 7 — Llama LLM runner (ExecuTorch)
- `ExecuTorchModelRunner.generate(prompt, maxTokens): String` loads `llama3_2_1b_instruct.pte` via
  the ExecuTorch LLM runner API; warm up off UI; never reload per inference.
- Recall-cue generation (§5.1, `max_tokens=60`, `temp=0.3`); answer generation (§5.2,
  `max_tokens=200`, `temp=0.3`); template fallback on >5 s timeout (`"This is {name}."`).
- Track `llm_generation` latency.
- **DoD:** Given an enrolled person + a stored session with transcript, pressing Recall generates a
  natural one-sentence cue within ~8 s total; fallback triggers correctly on timeout.

### CHECKPOINT 8 — Recall pipeline (full read path)
- `RecallEngine.captureRecallContext()` → identify → `RecallContextDto` (Case A/B/C).
- `RetrievalRanker.retrieve(ctx)` → SQL filter by `person_id` + vicinity (75 m), sort by recency,
  top 5.
- `QuestionSuggestionEngine.suggest(sessions)` → templates + optional LLM rewrite.
- `AnswerGenerator.answer(question, sessions)` → LLM grounded answer + `RecallAnswerDto` evidence.
- `RecallScreen` renders Case A (name + suggestions + answers + evidence), Case B (unknown → enroll),
  Case C (location-based suggestions).
- **DoD (airplane mode):** Enroll "Sarah" → store 2–3 sessions via `forceTick()` → press Recall at
  Sarah → name + suggestions appear → tap a question → answer with evidence.

### CHECKPOINT 9 — Enrollment flow
- `EnrollmentScreen` captures 5 frames via CameraX; extracts + averages landmark vectors.
- `PersonEnrollmentManager.enroll(personId, name, contextNotes)` updates the profile name.
- After enrollment the same person is recognized as Case A in future ticks.
- `enrollPerson()` callable via Pigeon.
- **DoD:** New person → Case B + enroll prompt; enter "Priya", confirm; point at Priya again → Case A,
  name shown. **No images stored at any point.**

### CHECKPOINT 10 — Latency dashboard & polish
- Latency tracking per stage: `face_detection`, `landmark_extraction`, `landmark_matching`,
  `whisper_encoder`, `whisper_decoder`, `llm_generation`, `total_pipeline`.
- `DebugScreen` shows a live latency table, color-coded (🟢 <100 ms, 🟡 100–500 ms, 🔴 >500 ms).
- `DemoContextProvider` (scripted) + `DemoSeeder` (realistic people/sessions) for a reliable demo.
- Graceful error states: model-load failure → dialog + fallback; no face → "Listening…"; unknown →
  enroll prompt; LLM timeout → template fallback.
- Airplane mode verified end to end — zero runtime network calls.
- **DoD:** Full demo runs without code changes: `seedDemoData()` once, service started, Recall on an
  enrolled face, natural cue spoken, question answered with evidence, all in airplane mode, latency
  dashboard live, no crash for 10 minutes of continuous use.

---

## 21. Build & run

```bash
# Generate Pigeon bindings (after any API change)
flutter pub run pigeon --input pigeons/memory_backend.dart

# Install Flutter dependencies
flutter pub get

# Run on S25 Ultra (USB debugging enabled)
flutter run
```
Grant on first launch: Camera, Microphone, Location, Notifications.

---

## 22. Open questions & tuning parameters

| Parameter | Default | Notes |
| --- | --- | --- |
| `TAU_KNOWN` | 0.92 | Tune after CP4 testing on real faces. |
| Tick cadence | ~5 s | Tune for battery impact in CP6. |
| `VICINITY_RADIUS_M` | 75 m | Adjust if location retrieval is too narrow/broad. |
| Whisper QNN compatibility | — | Verify `metadata.json` tensor specs match the QNN SDK version on the S25 Ultra. |
| Llama generation latency | — | If >8 s on device, reduce `max_tokens` or switch to template-only. |
| Ranking | recency DESC | Pure recency for MVP; revisit only if needed post-CP8. **No vector ranking** (no embeddings). |
| Enrollment samples | 5 frames | Increase if matching accuracy is low. |
| Case C retrieval | SQL + recency | Original v1 idea used vector search here; v2 intentionally drops it (no embeddings). Revisit only if recall quality is poor. |

---

*This document is the contract. Sub-agents: if you must deviate, update this file in the same change
and note it in §22.*
