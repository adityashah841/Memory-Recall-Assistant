# Memory Recall Assistant — Implementation Plan

> **Status:** Authoritative build specification for sub-agents.
> **Source of truth:** This document. If code and this document disagree, fix the code or raise an
> open question (Section 17) — do not silently diverge.
> **Golden rule:** Schemas (Section 5), insertion/querying flows (Sections 8–9), the recall pipeline
> (Section 10), and the Flutter↔Kotlin API (Section 11) must stay mutually consistent. A change to
> one requires a matching change to the others.

---

## 1. Overview, goals, and hackathon principles

### 1.1 What we are building
A multimodal, on-device **memory recall assistant**. The phone continuously and *periodically*
observes the world through the camera (and a short rolling audio window), and quietly writes
**compact memory records** to a local knowledge graph. Later, the user presses a **Recall** button;
the app figures out *who/where* it is looking at, retrieves the relevant memories, **suggests the
questions the user is likely to ask**, and **answers them with cited evidence** (time, place,
thumbnail, transcript snippet).

Motivating example: you meet someone you have met before. You press Recall. The app says "This is
**Priya**, you last spoke at the **office cafe** about her **Kyoto trip**", suggests
"What did Priya say about Kyoto?", and answers from stored transcripts.

### 1.2 Goals
- **G1 — Continuous compact capture.** Background loop produces small memory records on meaningful
  events. Never an always-on full A/V recorder.
- **G2 — Local knowledge graph.** Two-table store (people + sessions) on-device via Room/SQLite.
- **G3 — On-device AI.** Face, image, text embeddings, ASR, and a small LLM all run locally via
  ExecuTorch (hard requirement — see Section 6). No cloud inference.
- **G4 — Button-triggered recall.** Identify subject → retrieve → rank → suggest questions →
  answer with evidence.
- **G5 — Clean Flutter/Kotlin split.** Flutter owns UX; Kotlin owns Android-native reliability.

### 1.3 Hackathon principles (non-negotiable)
- **Flutter owns UX; Kotlin owns Android-native reliability.** Do not push capture/storage/inference
  into Dart; do not push navigation/screens into Kotlin.
- **Store events, not streams.** Persist event summaries, thumbnails, metadata, and embeddings only
  when something meaningful happens.
- **Template-first intelligence.** Question suggestions and answers start from deterministic
  templates over retrieved rows; the LLM only *rewrites/polishes* and *synthesizes* — retrieval and
  evidence are always grounded in stored rows.
- **Degrade gracefully.** Missing audio, missing location, no face, or a cold model must never crash
  the capture loop or the recall flow.

---

## 2. Target device & constraints

- **Primary device:** Samsung **Galaxy S25 Ultra** (Snapdragon 8 Elite class SoC, NPU/GPU available,
  ≥12 GB RAM). All model sizes below are chosen to run comfortably here.
- **OS target:** Android 14+ (`minSdk 31`, `targetSdk 34`+). Foreground-service + runtime-permission
  model assumed.
- **Offline-first:** All inference is local. Network is optional and must not be on the capture or
  recall critical path.
- **Thermals/battery:** The capture loop is *periodic / event-gated*, not free-running per frame, to
  bound heat and battery (see Section 4.1 cadence).
- **Storage:** Frames stored as compressed JPEG thumbnails; audio stored as short compressed clips;
  embeddings stored as `float32` blobs. Old rows are prunable (Section 14.4).

---

## 3. High-level architecture

```text
+--------------------------------------------------------------+
|                         FLUTTER (Dart)                       |
|  UI / UX only                                                |
|  - RecallScreen      (press-to-recall, suggestions, answers) |
|  - TimelineScreen    (browse stored sessions)                |
|  - EnrollmentScreen  (name/label unknown people)             |
|  - DebugScreen       (force tick, seed demo, inspect DB)     |
|  Controllers call -> MemoryBackendApi (Pigeon)               |
+----------------------------+---------------------------------+
                             |  Pigeon typed platform channel
                             v
+--------------------------------------------------------------+
|                        KOTLIN (Android)                      |
|  Native reliability                                          |
|                                                              |
|  bridge/        MemoryBackendChannel  (Pigeon host impl)     |
|                 DtoMappers                                   |
|                                                              |
|  service/       MemoryForegroundService  (lifecycle)         |
|                 CaptureLoop              (periodic tick)     |
|                                                              |
|  capture/       CameraFrameProvider (CameraX)                |
|                 TranscriptBuffer    (rolling ~60s ASR)       |
|                 LocationProvider    (fused + room label)     |
|                 DemoContextProvider (scripted demo input)    |
|                                                              |
|  recall/        RecallEngine                                 |
|                 RetrievalRanker                              |
|                 QuestionSuggestionEngine                     |
|                 AnswerGenerator                              |
|                                                              |
|  ai/            OnDeviceModelRunner (iface)                  |
|                 ExecuTorchModelRunner (committed impl)       |
|                 FakeModelRunner       (tests/CI only)        |
|                                                              |
|  enrollment/    PersonEnrollmentManager                      |
|                                                              |
|  data/          MemoryDatabase (Room)                        |
|                 PersonProfileEntity / MemorySessionEntity    |
|                 PersonDao / SessionDao                       |
|                 MemoryRepository (single entry point)        |
|                 DemoSeeder                                   |
+--------------------------------------------------------------+
```

**Data direction:**
- **Capture (write path):** `CaptureLoop` → `capture/*` providers → `ai/*` embeddings →
  decision (Section 8) → `MemoryRepository` → Room.
- **Recall (read path):** Flutter `RecallScreen` → Pigeon → `RecallEngine` →
  `MemoryRepository` (retrieval) → `RetrievalRanker` → `QuestionSuggestionEngine` →
  `AnswerGenerator` → Pigeon → Flutter.

---

## 4. Core working (strict reproduction of requirements)

This section reproduces the required behavior verbatim in intent. **Do not deviate.**

### 4.1 Camera is always live, captured periodically
- The camera is **always live** while the `MemoryForegroundService` runs, capturing in a **periodic**
  fashion via `CaptureLoop`.
- A **tick** fires on a cadence (default **every ~5 s**, configurable) and/or on an event trigger
  (face detected / significant scene change). Each tick may *skip* persistence if nothing meaningful
  is present (still avoid always-on recording).

### 4.2 Knowledge graph — what is available at a tick / how we query it
At each tick the system has:
- **One or more frames** available from the camera.
- A frame **may or may not contain a person**. If a person is identified → mark with `person_id`;
  otherwise → mark as **unknown**.
- **Conversation history / voice recording of the last ~1 minute** (rolling `TranscriptBuffer`).
- **Location** (fused location + manual room/location label).

### 4.3 Knowledge graph — data structure (table-based storage, 2 tables)
See Section 5 for the exact schema. Two tables only:
1. **`person_profile_table`** — canonical people we have encountered (the face-identity index).
2. **`session_table`** — time-stamped events: who (nullable) + where + frame + voice + embeddings.

### 4.4 Identifying a person
Check whether the **person's face in the current frame matches a person in
`person_profile_table`** (face-embedding cosine similarity ≥ threshold). See Section 7 for the exact
algorithm.

### 4.5 The three cases (insertion + querying)
Reproduced exactly in Sections 8 (insertion) and 9 (querying):
- **Known person** (matched in `person_profile_table`).
- **Unknown / new person** (a person is present and is the subject, but does not match any profile).
- **No person** in the frame.

---

## 5. Data model (exact schema)

> Storage engine: **Room over SQLite** (Kotlin). **v2 landmark schema (Checkpoint 2.5).** The only
> stored vector is `person_profile.landmark_vector` (face-mesh geometry), a `float32[936]`
> little-endian blob, **L2-normalized at write time** so similarity = dot product. Identity matching
> is a **linear landmark scan** over all profiles (Section 7) — no ANN index required at hackathon
> scale. **No face images and no learned embeddings are stored** (privacy); session rows carry no
> vectors. See §22 for the v1→v2 migration history.

### 5.1 `person_profile_table` (entity: `PersonProfileEntity`)
The canonical record per distinct person. **Identity index.**

| Column | Type | Null? | Notes |
| --- | --- | --- | --- |
| `person_id` | INTEGER PK (autoincrement) | no | Assigned as new people are encountered. |
| `name` | TEXT | yes | Filled when known / during enrollment. |
| `landmark_vector` | BLOB (`float32[936]`) | no | Face-mesh landmark geometry (468 pts × (x, y)). **Identification only.** L2-normalized. |
| `context_notes` | TEXT | yes | Any context inferred at the time the person was added. |
| `created_at` | INTEGER (epoch ms) | no | |
| `updated_at` | INTEGER (epoch ms) | no | |

### 5.2 `session_table` (entity: `MemorySessionEntity`)
One row per persisted event (tick that produced a meaningful record).

| Column | Type | Null? | Notes |
| --- | --- | --- | --- |
| `session_id` | INTEGER PK (autoincrement) | no | |
| `person_id` | INTEGER FK → `person_profile_table.person_id` | **yes** | `NULL` when no person in frame. |
| `location_label` | TEXT | yes | Manual/known room or place label (e.g. "office cafe"). |
| `latitude` | REAL | yes | Fused location. |
| `longitude` | REAL | yes | Fused location. |
| `timestamp` | INTEGER (epoch ms) | no | Event time (`curr_timestamp`). |
| `frame_image` | TEXT (path) | no | Frame/thumbnail @ `timestamp`. |
| `voice_clip` | TEXT (path) | yes | Audio clip for window `[prev_timestamp, curr_timestamp]`. |
| `transcript` | TEXT | yes | ASR transcript for that same window. |
| `created_at` | INTEGER (epoch ms) | no | |

**Landmark decision (v2, Checkpoint 2.5):** identity is matched on
`person_profile.landmark_vector` (face-mesh geometry) via a linear scan (Section 7). The v1
embeddings (`face_embedding`, `frame_embedding`, `voice_embedding`) and the `reference_face_image`
were **removed**; session rows carry **no vectors**. `frame_image` remains a non-face scene/frame
thumbnail path. **Note:** Sections 7 and 10 still describe v1 embedding-based identification/retrieval
and are pending a v2 revision — tracked in §22.

### 5.3 Indices & relations
- Index `session_table(person_id)`; index `session_table(timestamp)`;
  index `session_table(latitude, longitude)` (or a coarse geohash column if added later).
- `person_id` is a nullable foreign key with `ON DELETE SET NULL`.

---

## 6. On-device AI model stack (committed, ExecuTorch-first)

**Hard requirement:** real on-device models from the start. `ExecuTorchModelRunner` is the committed
implementation behind `OnDeviceModelRunner`. `FakeModelRunner` exists **only** for unit tests/CI and
must never be wired into the demo build.

| Purpose | Committed model | Output | Runtime |
| --- | --- | --- | --- |
| Face **detection** | MediaPipe BlazeFace **or** ML Kit Face Detection | bbox + landmarks | on-device (non-ExecuTorch OK) |
| Face **embedding** (identity) | **MobileFaceNet** (ArcFace-trained) | `float32[512]`, L2-norm | **ExecuTorch** |
| **Frame/scene embedding** | **MobileCLIP-S2** image encoder | `float32[~512]`, CLIP space | **ExecuTorch** |
| **Voice/transcript embedding** | **MobileCLIP-S2** text encoder | `float32[~512]`, **same** CLIP space | **ExecuTorch** |
| **ASR** (voice → text) | **Whisper-base** (tiny fallback) | transcript text | **ExecuTorch** (fallback: Android `SpeechRecognizer`) |
| **LLM** (suggest rewrite + answer) | **Llama 3.2 1B Instruct** (3B optional), 4-bit quant | text | **ExecuTorch** |

Notes for implementers:
- **Detection vs. embedding are separate stages.** Detection (BlazeFace/ML Kit) yields a face crop;
  the crop is fed to the ExecuTorch **MobileFaceNet** embedder. Only the *embedding/inference* models
  are hard-required on ExecuTorch; detection may use MediaPipe/ML Kit for speed/reliability.
- **Shared CLIP space:** MobileCLIP-S2 image and text encoders output into the **same** vector space,
  enabling cross-modal retrieval (text query vs. stored image/text vectors). Keep image and text
  encoders from the **same** MobileCLIP variant.
- **ASR → text → embedding:** the "voice embedding" is the MobileCLIP **text** embedding of the ASR
  transcript (not a raw-audio embedding). This keeps voice and query embeddings in one space.
- **Model assets** live under `android/app/src/main/assets/models/` and are loaded lazily on first
  use; the runner must warm models off the UI thread.

### 6.1 `OnDeviceModelRunner` interface (Kotlin contract)
```kotlin
interface OnDeviceModelRunner {
    fun detectFaces(frame: Bitmap): List<FaceBox>                 // bbox + landmarks
    fun embedFace(faceCrop: Bitmap): FloatArray                   // 512-d, L2-normalized
    fun embedImage(frame: Bitmap): FloatArray                     // CLIP image space, L2-normalized
    fun embedText(text: String): FloatArray                       // CLIP text space, L2-normalized
    fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String // ASR
    fun generate(prompt: String, maxTokens: Int = 256): String    // LLM
    fun warmUp()                                                  // preload/compile models
}
```
- `ExecuTorchModelRunner` implements all of the above with the committed models.
- `FakeModelRunner` returns deterministic vectors/strings for tests only.

---

## 7. Person identification algorithm

Given the current frame at a tick (or at recall time):

1. **Detect** faces with the detector. If **no face** → treat as *no person* (Case C).
2. Select the **primary subject** face: largest bbox (closest/most central). Optionally require a
   minimum face size to avoid bystanders.
3. **Crop + align** the primary face; compute `q = embedFace(crop)` (512-d, L2-normalized).
4. **Match** against every `person_profile.face_embedding p_i`: `sim_i = dot(q, p_i)` (cosine, since
   both normalized).
5. Let `best = argmax_i sim_i`.
   - If `sim_best ≥ TAU_KNOWN` (default **0.60**, tune on-device) → **Known person**
     (`person_id = best`). → Case A.
   - Else → a person **is present but unmatched** → **Unknown / new person**. → Case B.
6. Thresholds are centralized constants (`TAU_KNOWN`, min face size) so they can be tuned in one
   place. Identity matching uses **only** `face_embedding` (never frame/voice embeddings).

---

## 8. Insertion flows (write path) — the three cases

`CaptureLoop` tick gathers: primary frame, detected primary person (or none), last-~60s transcript +
audio clip for `[prev_timestamp, curr_timestamp]`, and location. It computes `frame_embedding =
embedImage(frame)` and, if a transcript exists, `voice_embedding = embedText(transcript)`. Then it
runs Section 7 and branches:

### 8.A Known person
- **No change** to `person_profile_table`.
- **Insert one row** into `session_table`:
  `(person_id = matched id, location_label/lat/lng, timestamp, frame_image, voice_clip, transcript,
  frame_embedding, voice_embedding)`.

### 8.B Unknown / new person (person present and is the subject, but not matched)
- **Insert a new profile** into `person_profile_table`:
  `(person_id = new, name = NULL, reference_face_image = saved crop, face_embedding = q,
  context_notes = inferred-if-any, voice_id = NULL)`.
  - *Inferred context* (optional): may be produced by the LLM from the current transcript/scene; if
    nothing reliable, leave `NULL`.
- **Insert a new session** into `session_table` with the **new `person_id`** (same fields as 8.A).

### 8.C No person in the frame
- **Insert directly** into `session_table` with **`person_id = NULL`** (location, timestamp, frame,
  voice/transcript, and both embeddings as available).

### 8.D Skip conditions (avoid always-on recording)
A tick **does not persist** when none of these hold: a person is present, **or** there is a non-empty
transcript, **or** the scene changed meaningfully vs. the last stored frame. This keeps the store to
*meaningful events* only.

---

## 9. Querying / recall flows (read path) — the three cases

Triggered by the **Recall** button. The app captures the *current* context (frame → primary person
via Section 7, current location, optional current transcript) and branches:

### 9.A Known person
- **Filter** `session_table` by `person_id = matched id` **and** location in the **vicinity** of the
  current location (Section 10.2 vicinity rule).
- Build context from the filtered rows → **generate suggested queries** (Section 10.3).
- When the user **selects a query**, **immediately produce its answer** with evidence (Section 10.4).

### 9.B Unknown / new person
- The current subject does **not** match any profile → **inform the user that this person is
  unknown** (offer to enroll/name them via `EnrollmentScreen`). No history is available yet.

### 9.C No person in the frame
- **Filter** `session_table` by `person_id IS NULL` **and** location in the **vicinity** of the
  current location, then **vector-search** over the embedding space (Section 10.2) using the current
  context as the query → suggested queries → answer with evidence.

---

## 10. Recall pipeline (retrieval → rank → suggest → answer)

End-to-end inside `RecallEngine`:

```text
current context ─► identify subject (Sec 7) ─► branch (Sec 9)
        │
        ├─► RetrievalRanker.retrieve(filter)        # candidate session rows
        ├─► RetrievalRanker.rank(query, candidates) # recency + vector similarity
        ├─► QuestionSuggestionEngine.suggest(top)   # template-first, optional LLM rewrite
        └─► AnswerGenerator.answer(selected, top)   # grounded answer + evidence
```

### 10.1 Query construction
- The **query embedding** is `embedText(query_text)` in the shared CLIP space. `query_text` is built
  from the current context (e.g. detected person name/label + location + current transcript), or from
  a user-selected suggested question.

### 10.2 Retrieval & vicinity
- **Candidate narrowing first** (cheap SQL): apply the case filter (`person_id = X` /
  `person_id IS NULL`) and **vicinity** — same `location_label` **or** within a radius
  (default **75 m**) of `(latitude, longitude)`. Missing location → fall back to person-only filter.
- **Vector search** over the narrowed candidates: score each candidate by
  `max( dot(query, frame_embedding), dot(query, voice_embedding) )` (voice term skipped when null).
  Because frame and voice share the CLIP space, a single text query compares against both.

### 10.3 Ranking
`RetrievalRanker` final score = weighted blend of:
- **Vector similarity** (Section 10.2), and
- **Recency** (exponential decay on `timestamp`).
Default weights `0.7 * similarity + 0.3 * recency`; tunable constants. Return top-K (default 5).

### 10.4 Suggestions & answers (template-first)
- `QuestionSuggestionEngine`: derive candidate questions from top rows using **templates** (e.g.
  "What did {name} say about {topic} at {place}?", "When did I last see {name}?"), where `{topic}` is
  extracted from transcripts. The LLM may **rewrite** templates into natural phrasing but must not
  invent topics absent from retrieved rows.
- `AnswerGenerator`: given the selected question + top rows, the LLM **synthesizes** an answer
  **grounded** in those rows. The answer is returned **with evidence**: the cited session rows
  (timestamp, location, frame thumbnail path, transcript snippet). No evidence ⇒ respond
  "I don't have a memory of that."

---

## 11. Flutter ↔ Kotlin API contract (Pigeon)

Defined once in `pigeons/memory_backend.dart` and generated into Dart
(`lib/core/platform/memory_backend_api.dart`) and Kotlin (`bridge/`). Host = Kotlin
(`MemoryBackendChannel` implements the generated `MemoryBackendApi`). All calls are async.

### 11.1 DTOs (Pigeon classes)
```dart
class RecallContextDto {        // current context captured on Recall press
  int? personId;                // resolved subject, null if none/unknown
  String? personName;
  bool personKnown;             // false => Case B (unknown) or Case C (no person)
  bool personPresent;           // distinguishes Case B (true) vs Case C (false)
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

class EvidenceDto {            // one cited session row
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

class PersonProfileDto {                // v2: no face-image path (Checkpoint 2.5)
  int personId;
  String? name;
  String? contextNotes;
}

class MemorySessionDto {        // for timeline/debug
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

### 11.2 Host API (Kotlin implements; Flutter calls)
```dart
@HostApi()
abstract class MemoryBackendApi {
  // Service lifecycle (capture loop)
  void startCapture();
  void stopCapture();
  ServiceStateDto getServiceState();
  void forceTick();                                   // debug: run one capture tick now

  // Recall flow
  RecallContextDto captureRecallContext();            // identify subject from current frame
  List<SuggestionDto> getSuggestions(RecallContextDto ctx);
  RecallAnswerDto answerQuestion(RecallContextDto ctx, String suggestionId, String? freeText);

  // Enrollment (Case B follow-up)
  void enrollPerson(int personId, String name, String? contextNotes);

  // Timeline / debug
  List<MemorySessionDto> listSessions(int limit, int offset);
  List<PersonProfileDto> listPeople();
  void seedDemoData();                                // DemoSeeder
  void clearAllData();
}
```
Mapping between DTOs and Room entities lives in `bridge/DtoMappers.kt`. The Flutter side never sees
raw embeddings — only paths, text, and ids.

---

## 12. Repository structure

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
        memory_backend_channel.dart          # thin Dart wrapper / DI
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
        TranscriptBuffer.kt                  # rolling ~60s ASR window
        LocationProvider.kt                  # fused + manual label
        DemoContextProvider.kt               # scripted demo input
      data/
        MemoryDatabase.kt                    # Room DB
        PersonProfileEntity.kt
        MemorySessionEntity.kt
        PersonDao.kt
        SessionDao.kt
        MemoryRepository.kt                  # single entry point over DAOs
        DemoSeeder.kt
      recall/
        RecallEngine.kt
        RetrievalRanker.kt
        QuestionSuggestionEngine.kt
        AnswerGenerator.kt
      ai/
        OnDeviceModelRunner.kt               # interface (Sec 6.1)
        ExecuTorchModelRunner.kt             # committed impl
        FakeModelRunner.kt                   # tests/CI only
      enrollment/
        PersonEnrollmentManager.kt
    app/src/main/assets/models/              # ExecuTorch model assets
```

---

## 13. Module responsibilities (per-file)

### 13.1 Flutter
- **`main.dart` / `app.dart`** — bootstrap, theming, routing across the four screens; request runtime
  permissions; obtain the `MemoryBackendApi` instance.
- **`core/models/*`** — plain Dart models mirroring the Pigeon DTOs for use in widgets/controllers.
- **`core/platform/memory_backend_api.dart`** — Pigeon-generated bindings (do not hand-edit).
- **`core/platform/memory_backend_channel.dart`** — thin wrapper/DI around the generated API; central
  place for error handling.
- **`features/recall/*`** — `RecallScreen` press-to-recall UI; `RecallController` calls
  `captureRecallContext` → renders Case A/B/C; shows suggestions; on tap calls `answerQuestion` and
  renders the answer + evidence cards.
- **`features/timeline/*`** — paged list of `MemorySessionDto` via `listSessions`.
- **`features/enrollment/*`** — name/label an unknown person (Case B) via `enrollPerson`.
- **`features/debug/*`** — `startCapture`/`stopCapture`/`forceTick`/`seedDemoData`/`clearAllData`,
  show `ServiceStateDto`.

### 13.2 Kotlin — bridge & service
- **`MainActivity.kt`** — Flutter activity; registers the Pigeon host; holds permission results.
- **`bridge/MemoryBackendChannel.kt`** — implements the generated host API; delegates to
  `service`, `recall`, `data`, `enrollment`. No business logic here beyond delegation + mapping.
- **`bridge/DtoMappers.kt`** — Room entity ⇄ Pigeon DTO conversions; never leaks embeddings to Dart.
- **`service/MemoryForegroundService.kt`** — foreground service; persistent notification; owns the
  `CaptureLoop` lifecycle; survives app backgrounding.
- **`service/CaptureLoop.kt`** — the periodic tick (Section 4.1); orchestrates providers → embeddings
  → Section 8 decision → `MemoryRepository`; enforces skip conditions (Section 8.D).

### 13.3 Kotlin — capture providers
- **`capture/CameraFrameProvider.kt`** — CameraX; exposes latest frame(s) as `Bitmap`; keeps camera
  live while service runs.
- **`capture/TranscriptBuffer.kt`** — maintains a rolling ~60 s window of audio + ASR transcript;
  exposes the clip + transcript for `[prev_timestamp, curr_timestamp]`.
- **`capture/LocationProvider.kt`** — Fused Location Provider + manual room/location label mapping.
- **`capture/DemoContextProvider.kt`** — scripted frames/transcripts/locations to drive a reliable
  live demo without depending on the environment.

### 13.4 Kotlin — recall
- **`recall/RecallEngine.kt`** — orchestrates Section 10 (identify → retrieve → rank → suggest →
  answer) and the Case A/B/C branching for querying (Section 9).
- **`recall/RetrievalRanker.kt`** — SQL candidate narrowing (person/vicinity) + vector scoring +
  recency blend (Section 10.2–10.3).
- **`recall/QuestionSuggestionEngine.kt`** — template-first question generation + optional LLM
  rewrite (Section 10.4).
- **`recall/AnswerGenerator.kt`** — grounded answer synthesis + evidence assembly (Section 10.4).

### 13.5 Kotlin — AI
- **`ai/OnDeviceModelRunner.kt`** — interface (Section 6.1).
- **`ai/ExecuTorchModelRunner.kt`** — committed implementation loading the Section 6 models from
  assets; warm-up off the UI thread.
- **`ai/FakeModelRunner.kt`** — deterministic stand-in for unit tests/CI only.

### 13.6 Kotlin — data & enrollment
- **`data/MemoryDatabase.kt`** — Room DB definition + migrations.
- **`data/PersonProfileEntity.kt` / `MemorySessionEntity.kt`** — exact schema (Section 5).
- **`data/PersonDao.kt` / `SessionDao.kt`** — queries incl. person-filter, vicinity-filter,
  paging, and candidate fetch for vector scoring.
- **`data/MemoryRepository.kt`** — single entry point used by `CaptureLoop`, `RecallEngine`,
  `enrollment`; owns insertion logic for Cases A/B/C (Section 8).
- **`data/DemoSeeder.kt`** — inserts a coherent demo graph (people + sessions + embeddings).
- **`enrollment/PersonEnrollmentManager.kt`** — set/update name + context on a profile (Case B).

---

## 14. Permissions & foreground-service lifecycle

### 14.1 Permissions
- `CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION` (+ `ACCESS_COARSE_LOCATION`),
  `FOREGROUND_SERVICE` and the typed `FOREGROUND_SERVICE_CAMERA` / `FOREGROUND_SERVICE_MICROPHONE` /
  `FOREGROUND_SERVICE_LOCATION` (Android 14+), `POST_NOTIFICATIONS`.
- Flutter requests runtime permissions before `startCapture`; Kotlin re-checks and fails safely if
  any are missing.

### 14.2 Service lifecycle
- `startCapture` → start `MemoryForegroundService` (foreground type camera|microphone|location) with
  a persistent notification → service starts `CaptureLoop`.
- `stopCapture` → stop loop → stop foreground/service.
- The loop must keep running while the app is backgrounded; it must stop cleanly on `stopCapture` and
  on service destruction.

### 14.3 Threading
- All capture, embedding, ASR, and LLM work runs **off the main thread** (coroutines /
  `Dispatchers.Default`/`IO`). Pigeon host methods return on background dispatchers and must not block
  the platform thread.

### 14.4 Retention
- Provide `clearAllData` (debug) and an optional pruning policy (e.g. cap session count / age) so the
  store stays bounded. Deleting a profile sets dependent `session.person_id` to `NULL`.

---

## 15. Demo seeding & fake data

- **`DemoSeeder` (Kotlin)** populates a realistic knowledge graph: a few `person_profile` rows (with
  face embeddings) and a spread of `session_table` rows across a couple of `location_label`s and
  timestamps, each with frame + transcript + embeddings — enough to exercise all three recall cases.
- **`DemoContextProvider` (Kotlin)** supplies scripted current-context inputs for a deterministic live
  demo (so Recall works even in a noisy hackathon room).
- **Debug screen** exposes `seedDemoData`, `forceTick`, and `clearAllData`.
- **`FakeModelRunner`** is for automated tests only — the on-device demo uses
  `ExecuTorchModelRunner` with real models (Section 6).

---

## 16. Build/run & milestone sequencing (no dates)

**Suggested milestones (each independently demoable):**
1. **Skeleton & bridge.** Flutter app shell + four screens (stubbed) + Pigeon API generated +
   `MemoryBackendChannel` returning canned data. Verify Flutter↔Kotlin round-trip.
2. **Data layer.** Room DB, entities, DAOs, `MemoryRepository`, `DemoSeeder`; Timeline screen reads
   real rows; `seedDemoData`/`clearAllData` work.
3. **Capture loop (no AI).** Foreground service + CameraX frame + location + rolling transcript
   buffer; persists Case C (no-person) rows on a tick. Debug `forceTick` works.
4. **AI runner.** `ExecuTorchModelRunner` with MobileFaceNet + MobileCLIP-S2 (image+text) + Whisper +
   Llama 3.2 1B; embeddings/ASR/LLM callable; warm-up off UI thread.
5. **Identification & full insertion.** Wire Section 7 + Cases A/B (known/new) into the capture loop.
6. **Recall pipeline.** `RecallEngine` + `RetrievalRanker` + `QuestionSuggestionEngine` +
   `AnswerGenerator`; RecallScreen renders suggestions + answers + evidence for all three cases.
7. **Enrollment & polish.** Case B enrollment flow; thresholds tuned on-device; thermals/latency
   pass on the S25 Ultra.

**Build/run:**
- `flutter pub get`; generate Pigeon: `flutter pub run pigeon --input pigeons/memory_backend.dart`.
- Place ExecuTorch `.pte` model assets under `android/app/src/main/assets/models/`.
- `flutter run` on the S25 Ultra (USB debugging). Grant camera/mic/location/notification permissions.

---

## 17. Open questions / future work

- **ANN index.** Linear scan over a narrowed candidate set is fine at hackathon scale; revisit an
  on-device ANN (e.g. HNSW) if session counts grow large.
- **Speaker diarization / `voice_id`.** `voice_id` is reserved; a speaker-embedding model could later
  strengthen identification when faces are absent.
- **Inferred `context_notes` quality.** LLM-inferred context at enrollment (Case B) may be noisy;
  decide whether to gate it behind user confirmation.
- **Vicinity radius & thresholds.** `TAU_KNOWN` (0.60), vicinity radius (75 m), ranking weights
  (0.7/0.3), and tick cadence (~5 s) are defaults to be tuned on the device.
- **Multi-person frames.** MVP picks a single primary subject; multi-subject sessions are future work.

---

This document is the contract. Sub-agents: if you must deviate, update this file in the same change
and note it in §22.

---

## 22. Deviations & schema changelog

> The task workflow references §§18–22; in this revision of the plan only §22 (this section) is
> present. Sections 18–21 were not authored here. Record every deviation from the contract below.

### Checkpoint 2.5 — v1 embeddings → v2 landmark schema
**What changed.** Migrated the data layer off learned embeddings to face-mesh **landmark geometry**,
driven by the privacy constraint *"do not store face images or embeddings."*

- `person_profile_table`: removed `reference_face_image`, `face_embedding`, `voice_id`; added
  `landmark_vector` BLOB `float32[936]` (non-null, L2-normalized).
- `session_table`: removed `frame_embedding`, `voice_embedding`; FK + the three indices retained.
- `EmbeddingCodec` → `LandmarkCodec` (generic `float32[]` ↔ blob + L2 norm).
- `PersonProfileDto`: dropped `referenceFaceImagePath`; Pigeon bindings regenerated.
- `MemoryDatabase`: version 1 → 2 with `fallbackToDestructiveMigration(dropAllTables = true)`
  (hackathon-stage; no hand-written migration).
- §5 and §11.1 updated above to match.

**Still inconsistent (follow-up needed).** §7 (identification) and §10 (recall/retrieval) still
describe the v1 embedding approach (`face_embedding` cosine match; `frame`/`voice` CLIP vector
search). They must be revised to the v2 landmark scan + non-vector retrieval in a later checkpoint.
The recall pipeline remains stubbed in code until then, so no behavior depends on the stale text yet.
