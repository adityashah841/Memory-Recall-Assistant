# Memory Recall Assistant — Implementation Plan (v4.0, CavaFace + Center-Crop)

> **Status:** Authoritative build specification for all sub-agents and teammates.
> **Device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite for Galaxy, 12 GB RAM) — or Meta AI
> Glasses + paired Snapdragon phone (same inference pipeline).
> **Framework:** Flutter (UX) + Kotlin (Android native), bridged by **Pigeon**.
> **Key change from v3:** Consent complexity removed; **center-crop targeting** added — only the face
> closest to the frame center is identified, so the app targets the person the user is actively
> talking to, not bystanders.
> **Golden rule:** Schemas (§5), insertion/querying flows (§10–§11), the recall pipeline (§12), and
> the Flutter↔Kotlin API (§13) must stay mutually consistent. A change to one requires a matching
> change to the others. If code and this document disagree, fix the code — do not silently diverge.

---

## 0. Current status

> **v4.2 (post-hackathon):** a second, swappable AI backend (Google on-device: ML Kit + FaceNet/LiteRT +
> Android SpeechRecognizer + Gemini Nano) makes the full pipeline run live on non-Qualcomm phones
> (verified on a Pixel 10). The Qualcomm stack below remains the primary design and is selected
> automatically when its native bridge + models are present. Details: §24.8.

| Checkpoint | State | Notes |
| --- | --- | --- |
| **CP1 — Skeleton & Pigeon bridge** | ✅ Done | |
| **CP2 / 2.5 / 2.6 — Data layer + migrations** | ✅ Done | v4 `face_embedding float32[512]` schema. |
| **CP3 — Model runners** | 🟡 Qualcomm native pending · ✅ Google backend | QNN/Genie graph I/O is a skeleton; ExecuTorch inference gated (§24.7). |
| **CP4 — FaceEmbeddingEngine + center crop** | ✅ Done | Live on Pixel 10 (§24.8). |
| **CP5 — ASR** | 🟡 Whisper native pending · ✅ live on-device SpeechRecognizer | |
| **CP6 — Capture loop** | ✅ Done | Conversation-level session merging (§24.8). |
| **CP7 — LLM + recall pipeline** | ✅ Done (Gemini Nano) | Genie path pending native bridge. |
| **CP8 — Enrollment** | ✅ Done | |
| **CP9 — Demo polish** | 🟡 | Spoken cues/answers, live preview + reticle done; latency dashboard not built. |

---

## 1. Why v4 exists (decision log)

| Version | Key decision |
| --- | --- |
| v1 | CavaFace float TFLite — original plan |
| v2 | Landmark geometry — privacy pivot after judge feedback (~64% uncontrolled accuracy) |
| v3 | CavaFace w8a16 ExecuTorch — reverted after accuracy concern + consent UX added |
| v4 | CavaFace w8a16 — consent complexity removed, **center-crop targeting** added |
| v4.1 | **Model formats corrected to as-delivered:** CavaFace + Whisper are **QNN context binaries** (`.bin`), the LLM is a **QNN Genie** bundle (not `.pte`); only detection + landmark are ExecuTorch. Weights **pushed to device**, not bundled. |
| v4.2 | **Second backend:** Google on-device stack (ML Kit · FaceNet/LiteRT · SpeechRecognizer · Gemini Nano) behind the same `OnDeviceModelRunner`; runs on any recent Android phone. Recall cues/answers spoken via TTS; live camera preview. |

v4 is the clean, buildable version. **No** consent screen, **no** `ConsentManager`, **no**
`LandmarkIdentityEngine`. Just: detect faces → find center face → align → embed → match.

---

## 2. Model stack (final, locked for v4)

Model files are **pushed to the device** (not bundled in the APK) and loaded by path from the app's
external files dir — see §16. The small `.pte` may optionally be bundled; the large `.bin`/Genie
weights must not be (size + GitHub limits).

| Purpose | File | Format | Runtime | Quantization |
| --- | --- | --- | --- | --- |
| Face detection | `mediapipe_face_detection.pte` | ExecuTorch | QNN/NPU | float |
| Face landmark (**alignment only**) | `mediapipe_face_landmark_detector.pte` | ExecuTorch | QNN/NPU | float |
| Face embedding / identity | `cavaface.bin` | **QNN context binary** | QNN/NPU | **w8a16** |
| ASR encoder | `whisper_encoder.bin` | **QNN context binary** | QNN/NPU | **w8a16** |
| ASR decoder | `whisper_decoder.bin` | **QNN context binary** | QNN/NPU | **w8a16** |
| LLM (cue + answer) | `llama_v3_2_1b_instruct` Genie bundle (`part{1,2,3}_of_3.bin` + `genie_config.json` + `tokenizer.json`) | **QNN Genie** | QNN/NPU | **w4** |
| ASR tensor specs | `metadata.json` | JSON | reference | — |

> **v4.1 correction — model formats as actually delivered (Snapdragon 8 Elite, `soc_model 69`,
> `dsp_arch v79`).** Three on-device runtimes are in play, not just ExecuTorch:
> - **ExecuTorch (`.pte`):** face **detection** + **landmark** only.
> - **QNN context binary (`.bin`):** **CavaFace** (`cavaface.bin`) and **Whisper**
>   (`whisper_encoder.bin` + `whisper_decoder.bin`), loaded via the raw QNN context-binary API.
> - **QNN Genie:** the **LLM** (Llama 3.2 1B Instruct, w4) ships as a Genie bundle — 3 context-binary
>   parts + `genie_config.json` + `tokenizer.json` — driven through the QAIRT **Genie** dialog API
>   (`QnnHtp` backend). It is **not** a single `.pte`.
>
> All three runtimes need the matching **QAIRT 2.45** runtime `.so` libs (the binaries were built with
> qairt 2.45.0). See §16 for on-device placement (models are **pushed to the device**, not bundled).

### 2.1 CavaFace specs
- Input: `float32[1, 3, 112, 112]` — CHW, pixel values normalized to `[-1, 1]`.
- Output: `float32[1, 512]` — **L2-normalized ArcFace embedding**.
- Preprocessing: resize to 112×112, then per channel `pixel = (pixel / 127.5) - 1.0`.
- Matching: cosine similarity via dot product (both vectors L2-normalized).
- Match threshold: `TAU_KNOWN = 0.60f`.
- Model size: ~62 MB quantized (w8a16) — load once at service start, keep warm, never reload.

> **Only face detection + landmark are ExecuTorch.** CavaFace and Whisper load via the QNN SDK
> context-binary API; the LLM loads via QNN Genie. MediaPipe landmarks are used for **alignment
> only**, never for identity.
>
> ⚠️ **CavaFace I/O caveat:** the `float32[1,3,112,112]` input / `float32[1,512]` output above is the
> *logical* spec. As a **w8a16 QNN** binary, `cavaface.bin`'s actual graph I/O is **quantized**
> (scale/zero-point per tensor) and there is **no `metadata.json` for it** (unlike Whisper). The exact
> input/output tensor names, dtypes and quant params must be read from the `.bin` (or supplied by the
> model authors) before the embedder can be wired.

---

## 3. Identity architecture — center-crop targeting

### 3.1 The core idea
When a user talks to someone, that person is naturally in the **center** of the frame. Rather than
picking the **largest** face (which may be a bystander who is simply closer), the app identifies the
face whose bounding-box center is **closest to the frame center**. Targeting becomes intentional: the
user points the camera at who they're talking to, and the app targets that person. For Meta Glasses
this is especially reliable — the camera points where the user looks.

### 3.2 Full pipeline
```text
Camera frame (W × H pixels)
        ↓
[mediapipe_face_detection.pte]      →  List<FaceBox> (all faces + bboxes + confidence)
        ↓
CENTER-CROP SELECTION:
  frame center = (W/2, H/2)
  for each face: dist = sqrt((face.centerX - W/2)² + (face.centerY - H/2)²)
  select face with MIN dist; reject if dist > MAX_CENTER_OFFSET_PX (not centered)
  also reject faces smaller than MIN_FACE_SIZE_PX
        ↓
Crop selected face region
        ↓
[mediapipe_face_landmark_detector.pte]  →  5 key points (eyes, nose, mouth corners)
  used for geometric ALIGNMENT only: rotate eye-line horizontal, scale to 112×112
        ↓
[cavaface.bin] via QNN SDK (context binary)
  input  float32[1, 3, 112, 112] (normalized aligned face; quantized w8a16 at the graph boundary)
  output float32[1, 512] (L2-normalized embedding; dequantized)
        ↓
Cosine similarity (dot product) vs all stored face_embedding in person_profile_table
        ↓
Score >= TAU_KNOWN (0.60)  →  Known person   (Case A)
Score <  TAU_KNOWN (0.60)  →  Unknown person (Case B)
No face near center        →  No person      (Case C)
```

### 3.3 Center-crop constants (single source of truth — `identity/IdentityConfig.kt`)
```kotlin
const val TAU_KNOWN = 0.60f             // ArcFace cosine threshold
const val MAX_CENTER_OFFSET_PX = 120f   // max px from frame center to be the "target" (~25% of 640w)
const val MIN_FACE_SIZE_PX = 80         // ignore very distant/small faces
const val ENROLLMENT_SAMPLES = 5        // frames averaged per enrollment
const val EMBEDDING_DIM = 512           // CavaFace output dimension
const val VICINITY_RADIUS_M = 75.0      // location vicinity for retrieval
```

### 3.4 Why center over largest face
| Strategy | Problem |
| --- | --- |
| Largest face | Targets whoever is physically closest, not who the user is talking to |
| Highest confidence | Same issue — physically closest wins |
| **Center face (v4)** | Targets who the user is pointing the camera at — intentional |

### 3.5 UI guidance overlay
Show a subtle circular reticle in the center of the camera preview (§14). When a face is locked
within `MAX_CENTER_OFFSET_PX`, the reticle turns **green**; otherwise it stays **grey**. This makes
the targeting model visible without explanation.

### 3.6 Enrollment center crop
During enrollment, apply the **same** center-crop selection — only enroll the face closest to the
frame center, so the enrolled embedding matches the camera angle used at recognition time.

### 3.7 Alignment (MediaPipe landmark role)
The landmark detector is used **only** for geometric alignment before CavaFace: detect 5 key points
(left/right eye centers, nose tip, left/right mouth corners), rotate so the eye line is horizontal,
scale so inter-ocular distance is standardized, output a 112×112 aligned face. This significantly
improves CavaFace accuracy for the slightly-angled faces common in real conversation.

---

## 4. FaceEmbeddingEngine (new in v4)

`identity/FaceEmbeddingEngine.kt` orchestrates the whole identity pipeline. It **replaces** the v2
`LandmarkIdentityEngine`.

```kotlin
class FaceEmbeddingEngine(private val runner: OnDeviceModelRunner) {

    /** Full pipeline for one frame: detect → select center face → align → embed → match.
     *  Returns MatchResult, or null if no face is near the frame center.
     *  Stages: face_detection + face_alignment + face_embedding + identity_match. */
    fun identify(frame: Bitmap, profiles: List<PersonProfileEntity>): MatchResult?

    /** Face whose bbox center is closest to frame center; null if none within MAX_CENTER_OFFSET_PX. */
    private fun selectCenterFace(faces: List<FaceBox>, frameWidth: Int, frameHeight: Int): FaceBox?

    private fun distanceToCenter(box: FaceBox, frameWidth: Int, frameHeight: Int): Float

    /** Align crop using 5 landmarks: rotate eyes horizontal, scale to standard inter-ocular distance.
     *  Output: 112×112 Bitmap. */
    private fun alignFace(crop: Bitmap, landmarks: FloatArray): Bitmap

    /** Preprocess aligned face for CavaFace: resize 112×112, CHW float32[1,3,112,112],
     *  normalize each channel pixel = (pixel/127.5) - 1.0. */
    private fun preprocess(alignedFace: Bitmap): FloatArray

    /** Cosine similarity = dot product (both vectors L2-normalized). */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float

    /** Enrollment: average N embedding vectors then L2-normalize. */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray

    private fun l2Normalize(v: FloatArray): FloatArray
}

data class MatchResult(
    val personId: Int,
    val name: String?,
    val similarityScore: Float,   // 0.0–1.0 cosine similarity
    val isKnown: Boolean,         // score >= TAU_KNOWN
    val faceBox: FaceBox          // for drawing the bounding-box / reticle overlay
)
```

---

## 5. Data schema

> Storage engine: **Room over SQLite**. The only stored vector is `face_embedding`
> (`float32[512]`, L2-normalized at write time so similarity = dot product). Retrieval is **SQL-based**
> (`person_id` + location + recency) — **no ANN index, no vector search**.

### 5.1 `person_profile_table` (entity: `PersonProfileEntity`)
| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `person_id` | INTEGER PK autoincrement | No | |
| `name` | TEXT | Yes | Filled at enrollment. |
| `face_embedding` | BLOB `float32[512]` | No | L2-normalized ArcFace (CavaFace) embedding. |
| `context_notes` | TEXT | Yes | Caregiver-provided or LLM-inferred. |
| `created_at` | INTEGER epoch ms | No | |
| `updated_at` | INTEGER epoch ms | No | |

### 5.2 `session_table` (entity: `MemorySessionEntity`)
| Column | Type | Nullable | Notes |
| --- | --- | --- | --- |
| `session_id` | INTEGER PK autoincrement | No | |
| `person_id` | INTEGER FK → `person_profile_table` | **Yes** | `NULL` when no person near center (Case C). `ON DELETE SET NULL`. |
| `location_label` | TEXT | Yes | Room or place label. |
| `latitude` | REAL | Yes | Fused location. |
| `longitude` | REAL | Yes | Fused location. |
| `timestamp` | INTEGER epoch ms | No | Event time. |
| `frame_image` | TEXT path | No | Compressed JPEG **scene** thumbnail. |
| `voice_clip` | TEXT path | Yes | Audio clip for the window. |
| `transcript` | TEXT | Yes | Whisper ASR output. |
| `created_at` | INTEGER epoch ms | No | |

> **No vector embedding columns on `session_table`.** Retrieval is SQL (`person_id` + location +
> recency).

### 5.3 Indices
```sql
CREATE INDEX idx_session_person_id ON session_table(person_id);
CREATE INDEX idx_session_timestamp ON session_table(timestamp);
CREATE INDEX idx_session_location  ON session_table(latitude, longitude);
```

---

## 6. ASR pipeline (Whisper via QNN) — unchanged from v3

> **Critical:** Whisper uses the **QNN context-binary** format (`.bin`), **NOT** ExecuTorch.

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
- Encoder input: `float32[1, 80, 3000]` (30 s of audio). Quantization: **w8a16**.
- Fallback: Android `SpeechRecognizer` if the QNN binary fails to load — **never crash**.

---

## 7. LLM pipeline (Llama 3.2 1B via QNN Genie)

> **Runtime (v4.1):** the LLM is **not** ExecuTorch. It is a **QAIRT Genie** bundle —
> `part{1,2,3}_of_3.bin` (w4 context binaries) + `genie_config.json` + `htp_backend_ext_config.json`
> + `tokenizer.json`. It is driven via the **Genie dialog API** (`GenieDialog_*`, `QnnHtp` backend),
> which owns tokenization, KV-cache and sampling internally. The prompts below are unchanged; only the
> runner changes (`GenieLlmRunner`, §8).

### 7.1 Recall-cue prompt
```text
<|begin_of_text|><|start_header_id|>system<|end_header_id|>
You are a private memory assistant. Generate one short warm sentence
to help the user remember the person in front of them.
Never mention memory loss. Maximum 2 sentences.
<|eot_id|><|start_header_id|>user<|end_header_id|>
Person: {name}
Last seen: {last_seen_timestamp}
Location: {location_label}
Recent conversation: {transcript_snippet}
Generate the recall cue:
<|eot_id|><|start_header_id|>assistant<|end_header_id|>
```

### 7.2 Answer-generation prompt
```text
<|begin_of_text|><|start_header_id|>system<|end_header_id|>
You are a private memory assistant. Answer the question using only
the provided memory records. If no evidence exists say
"I don't have a memory of that."
<|eot_id|><|start_header_id|>user<|end_header_id|>
Question: {selected_question}
Memory records:
{session_1_transcript}
{session_2_transcript}
{session_3_transcript}
Answer with evidence:
<|eot_id|><|start_header_id|>assistant<|end_header_id|>
```

### 7.3 LLM settings
- Recall cue: `max_tokens=60`. Answer: `max_tokens=200`.
- Sampler (from `genie_config.json`): `temp=0.8`, `top-k=40`, `top-p=0.95`, `seed=42`. Override per
  call to `temp≈0.3` for the deterministic recall/answer style if desired.
- Context size: **4096** tokens; `n-threads=3`; `QnnHtp` backend, `cpu-mask=0xe0`, `kv-dim=64`.
- BOS `128000`; EOS any of `[128001, 128008, 128009]`; RoPE llama3 scaling (`theta=500000`, `factor=8`).
- Template fallback on timeout > 5 s or Genie load failure: `"This is {name}."` — **never crash**.
- Quantization: **w4** (weights), Genie-managed activations.

---

## 8. Architecture overview

### 8.1 Flutter / Kotlin split
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
|              LocationProvider                            |
|              DemoContextProvider                         |
|                                                          |
| ai/          OnDeviceModelRunner (interface)             |
|              ExecuTorchModelRunner                       |
|                (mediapipe_face_detection.pte,            |
|                 mediapipe_face_landmark_detector.pte)    |
|              QnnModelRunner (raw QNN context binaries)   |
|                (cavaface.bin, whisper encoder+decoder)   |
|              GenieLlmRunner (Llama 3.2 1B Genie bundle)  |
|              FakeModelRunner (tests/CI only)             |
|                                                          |
| identity/    FaceEmbeddingEngine                         |
|                (center crop + alignment + CavaFace +     |
|                 dot product)                             |
|              IdentityConfig (TAU_KNOWN, offsets, …)      |
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
|              EmbeddingCodec (float32[]↔blob + L2 norm)   |
|              DemoSeeder                                  |
|                                                          |
| enrollment/  PersonEnrollmentManager                     |
+----------------------------------------------------------+
```

### 8.2 AI module split
| Class | Models | Runtime |
| --- | --- | --- |
| `ExecuTorchModelRunner` | `mediapipe_face_detection.pte`, `mediapipe_face_landmark_detector.pte` | ExecuTorch AAR |
| `QnnModelRunner` | `cavaface.bin`, `whisper_encoder.bin`, `whisper_decoder.bin` | QNN SDK (context binary) |
| `GenieLlmRunner` | `part{1,2,3}_of_3.bin` + `genie_config.json` + `tokenizer.json` | QAIRT Genie |
| `FaceEmbeddingEngine` | Orchestrates center crop → alignment → CavaFace → match | Detection/landmark via `ExecuTorchModelRunner`, embed via `QnnModelRunner` |
| `FakeModelRunner` | Deterministic stubs | Tests/CI only — **never** in demo build |

### 8.3 `OnDeviceModelRunner` interface
```kotlin
interface OnDeviceModelRunner {
    fun detectFaces(frame: Bitmap): List<FaceBox>
    fun extractLandmarks(faceCrop: Bitmap): FloatArray    // 5 key points for ALIGNMENT (10 floats)
    fun embedFace(alignedFace: Bitmap): FloatArray        // float32[512] L2-normalized (CavaFace)
    fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String
    fun generate(prompt: String, maxTokens: Int = 256): String
    fun warmUp()
}

data class FaceBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val confidence: Float
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}
```
> `extractLandmarks` returns **5 alignment key points** (10 floats: x0,y0,…,x4,y4) — not a 936-d
> identity vector. Identity is `embedFace` (CavaFace 512-d).

### 8.4 Data flow
```text
WRITE PATH (Capture):
CaptureLoop → CameraFrameProvider → FaceEmbeddingEngine
  (detect all faces → select center face → align → embed → match)
  → MatchResult → CaptureLoop decision (Case A/B/C)
  → TranscriptBuffer + LocationProvider
  → MemoryRepository → Room DB

READ PATH (Recall):
Flutter RecallScreen → Pigeon → RecallEngine
  → FaceEmbeddingEngine (identify center face in current frame)
  → MemoryRepository (SQL: person_id + location + recency)
  → RetrievalRanker → QuestionSuggestionEngine → AnswerGenerator
  → Pigeon → Flutter
```

---

## 9. Core working — camera, knowledge graph, identification

This reproduces the **core working** requirements. Sub-agents must not deviate.

### 9.1 Camera always live, captured periodically
The camera is **always live** while `MemoryForegroundService` runs, captured **periodically** by
`CaptureLoop` (~5 s tick, configurable, plus event triggers). A tick may **skip** persistence when
nothing meaningful is present (§10.4) — this is what keeps the app from being an always-on recorder.

### 9.2 What is available at a tick
- **One or more frames** from the camera.
- A frame **may or may not contain a person near center**. If a centered face is identified →
  `person_id`; otherwise → unknown / none.
- **Conversation / voice of the last ~30 s** (rolling `TranscriptBuffer`).
- **Location** (fused location + manual room/location label).

### 9.3 Knowledge graph = two tables
Exactly the two tables in §5: `person_profile_table` (the **CavaFace identity index**) and
`session_table` (time-stamped events: who (nullable) + where + scene frame + voice/transcript).

### 9.4 Identifying a person
Detect all faces, **select the center face** (§3.2), align via 5 landmarks, embed via CavaFace, and
**dot-product match against every `person_profile_table.face_embedding`**. `score >= TAU_KNOWN` ⇒
known; `< TAU_KNOWN` ⇒ unknown; no centered face ⇒ no person.

---

## 10. Insertion flows (write path — three cases)

`CaptureLoop` tick gathers: frame, center-face `MatchResult` (or none), last ~30 s transcript + audio
clip, and location. Then it branches:

### 10.A Known person (`score >= TAU_KNOWN`, face near center)
- **No change** to `person_profile_table`.
- **Insert one row** into `session_table` with `person_id = matched_id`.

### 10.B Unknown person (face near center, `score < TAU_KNOWN`)
- **Insert a new profile** with `name = NULL`, `face_embedding = query_embedding`.
- **Insert a new session** with the new `person_id`.

### 10.C No person near center
- **Insert a session** with `person_id = NULL` (still captures location + transcript if available).

### 10.4 Skip conditions (avoid always-on recording)
A tick **does not persist** unless at least one holds:
- a face is present near center, **or**
- there is a non-empty transcript, **or**
- the scene changed meaningfully vs. the last stored frame.

---

## 11. Querying / recall flows (read path — three cases)

Triggered by the **Recall** button. The app identifies the current **center** face (§3.2) and
branches:

### 11.A Known person
- **SQL-filter** `session_table` by `person_id = matched_id` **and** location in **vicinity**
  (same `location_label` **or** within `VICINITY_RADIUS_M`). Sort `timestamp DESC`, take **top 5**.
- Build suggested questions (§12.3); on selection, **immediately** produce the answer with evidence
  (§12.4).

### 11.B Unknown person
- Centered face does **not** match any profile ⇒ **"Unknown person — tap to enroll"** (route to
  `EnrollmentScreen`). No history yet.

### 11.C No person near center
- **SQL-filter** by `person_id IS NULL` **and** location vicinity, sort `timestamp DESC`, top 5 →
  location-based suggestions → answer with evidence. (No vector search.)

---

## 12. Recall pipeline (retrieval → rank → suggest → answer)

```text
current frame → FaceEmbeddingEngine.identify() → Case A/B/C branch
      │
      ├── RetrievalRanker.retrieve()      # SQL: person_id (or NULL) + location within 75m
      ├── RetrievalRanker.rank()          # timestamp DESC, top 5
      ├── QuestionSuggestionEngine.suggest()  # templates, optional LLM rewrite
      └── AnswerGenerator.answer()        # LLM grounded in top transcripts + evidence
```

### 12.1 Query construction
Built from the current context (person name/label + location + current transcript) or a selected
suggested question. **No embedding** is computed — retrieval is SQL.

### 12.2 Retrieval & vicinity
SQL filter (`person_id = X` / `IS NULL`) **AND** vicinity (same `location_label` **or** within
`VICINITY_RADIUS_M`). Missing location ⇒ person-only filter. Sort `timestamp DESC`, return **top 5**.

### 12.3 Question suggestions (template-first)
Templates over retrieved rows; the LLM may **rewrite** for natural phrasing but must not invent topics
absent from the rows:
- `"What did {name} say about {topic} at {place}?"`
- `"When did I last see {name}?"`
- `"What did we discuss at {location_label}?"`

### 12.4 Answer + evidence
`AnswerGenerator` calls the LLM (§7.2) grounded in the top transcripts and returns `RecallAnswerDto`
whose `evidence` lists the **cited** session rows (session id, timestamp, location, optional
thumbnail, transcript snippet). No evidence ⇒ `"I don't have a memory of that."`

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
  bool personKnown;      // true = Case A
  bool personPresent;    // true = face near center (A or B); false = Case C
  String? locationLabel;
  double? latitude;
  double? longitude;
  String? currentTranscript;
  String? frameThumbPath;
}

class SuggestionDto { String id; String questionText; }

class EvidenceDto {
  int sessionId;
  int timestampMs;
  String? locationLabel;
  String? frameThumbPath;
  String? transcriptSnippet;
}

class RecallAnswerDto { String answerText; List<EvidenceDto> evidence; }

class PersonProfileDto {   // no embedding field — never exposed to Dart
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

class ServiceStateDto { bool running; int sessionCount; int personCount; }
```

### 13.2 Host API (Kotlin implements; Flutter calls)
```dart
@HostApi()
abstract class MemoryBackendApi {
  // Service lifecycle
  void startCapture();
  void stopCapture();
  ServiceStateDto getServiceState();
  void forceTick();

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
> **Note:** `PersonProfileDto` is unchanged from the v2 plan (`personId`, `name`, `contextNotes`), so
> **CP2.6 needs no Pigeon regeneration** — the change is purely in the Kotlin data layer. The Flutter
> side never sees `face_embedding`.

---

## 14. UI — center-targeting overlay

The camera preview must show a targeting overlay so the center-crop behavior is visible:

```text
┌─────────────────────────────────┐
│                                 │
│         ╔═══════════╗           │
│         ║  ◎ target ║           │
│         ╚═══════════╝           │
│  [Person: Sarah  ████████░░ 87%]│
│            [ ▶ RECALL ]         │
└─────────────────────────────────┘
```

**Reticle states:**
- **Grey** circle — no face detected near center.
- **Yellow** circle (pulsing) — face detected, embedding in progress.
- **Green** circle + name — known person identified, cue ready.
- **Red** circle — face detected but unknown (Case B).

**Similarity-score bar** (shown when a face is identified): renders cosine similarity as a visual bar;
useful to show judges live matching. Only visible when the latency dashboard is open.

---

## 15. Repository structure

```text
memory_recall_app/
  pubspec.yaml
  pigeons/
    memory_backend.dart
  lib/
    main.dart
    app.dart
    core/
      models/ { recall_context.dart, memory_session.dart, person_profile.dart, recall_answer.dart }
      platform/ { memory_backend_api.dart (generated), memory_backend_channel.dart }
      theme/
    features/
      recall/ { recall_screen.dart, recall_controller.dart, widgets/ (incl. center_reticle.dart) }
      timeline/ { timeline_screen.dart, timeline_controller.dart }
      enrollment/ { enrollment_screen.dart, enrollment_controller.dart }
      debug/ { debug_screen.dart, debug_controller.dart }
  android/
    app/src/main/kotlin/<pkg>/
      MainActivity.kt
      bridge/        { MemoryBackendChannel.kt, DtoMappers.kt }
      service/       { MemoryForegroundService.kt, CaptureLoop.kt }
      capture/       { CameraFrameProvider.kt, TranscriptBuffer.kt, LocationProvider.kt, DemoContextProvider.kt }
      ai/            { OnDeviceModelRunner.kt, ExecuTorchModelRunner.kt, QnnModelRunner.kt, GenieLlmRunner.kt, ModelAssets.kt, FakeModelRunner.kt }
      identity/      { FaceEmbeddingEngine.kt, IdentityConfig.kt }
      recall/        { RecallEngine.kt, RetrievalRanker.kt, QuestionSuggestionEngine.kt, AnswerGenerator.kt }
      data/          { MemoryDatabase.kt, PersonProfileEntity.kt, MemorySessionEntity.kt,
                       PersonDao.kt, SessionDao.kt, MemoryRepository.kt, EmbeddingCodec.kt, DemoSeeder.kt }
      enrollment/    { PersonEnrollmentManager.kt }
    app/src/main/jniLibs/arm64-v8a/  # QAIRT 2.45 .so runtime libs (libQnnHtp*, libQnnSystem, libGenie*) + ExecuTorch .so
    app/libs/                        # executorch-android-release.aar
    # NOTE: model weights are NOT bundled in assets — pushed to the device (§16, option 2).
```

---

## 16. File placement

**Model weights are pushed to the device, not bundled (option 2)** — total ~1.8 GB (CavaFace+Whisper
~0.5 GB, Llama Genie ~1.3 GB) exceeds APK limits and GitHub's 100 MB/file cap. They are `adb push`ed
to the app's external files dir and loaded by path; `.gitignore` keeps them out of the repo.

```text
On device → /sdcard/Android/data/com.synapseriders.memory_recall_app/files/models/
├── mediapipe_face_detection.pte          ← ExecuTorch, float (~2 MB)
├── mediapipe_face_landmark_detector.pte  ← ExecuTorch, float (~3 MB)
├── cavaface.bin                          ← QNN context binary, w8a16 (~132 MB)
├── whisper/
│   ├── encoder.bin                       ← QNN context binary, w8a16 (~132 MB)
│   ├── decoder.bin                       ← QNN context binary, w8a16 (~225 MB)
│   └── metadata.json                     ← Whisper tensor specs
└── llama_genie/                          ← QAIRT Genie bundle (~1.3 GB)
    ├── part1_of_3.bin  part2_of_3.bin  part3_of_3.bin
    ├── genie_config.json  htp_backend_ext_config.json
    └── tokenizer.json  tokenizer_config.json  special_tokens_map.json  config.json

In repo (committed):
android/app/libs/                          ← optional manual ExecuTorch AAR override (ExecuTorch is
                                             pulled from Maven: org.pytorch:executorch-android:1.5.1)
android/app/src/main/jniLibs/arm64-v8a/   ← QAIRT 2.45 .so libs (NOT committed; per-dev local)
scripts/push_models.sh                     ← adb push helper (laptop → device)
```

---

## 17. Permissions

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

## 18. Threading rules (non-negotiable)
- All model inference runs on `Dispatchers.Default` — never the main thread.
- All Room queries run on `Dispatchers.IO` — never the main thread.
- All Pigeon host methods run on background dispatchers.
- UI updates only via `StateFlow` / `LiveData` collected on the main thread.
- Model **warm-up** happens once at service start — never per-inference.
- **CavaFace is ~62 MB** — load once, keep warm, never reload per tick.

---

## 19. What NOT to build (scope boundaries)

| | Item |
| --- | --- |
| ❌ | Largest-face selection (replaced by **center-face** selection) |
| ❌ | `LandmarkIdentityEngine` from v2 (replaced by `FaceEmbeddingEngine`) |
| ❌ | `ConsentManager` / `ConsentScreen` from v3 |
| ❌ | MobileCLIP or any scene/text embedding |
| ❌ | Vector similarity search (SQL + recency only) |
| ❌ | Cloud inference of any kind |
| ❌ | Network calls at runtime (airplane mode must work) |
| ❌ | Multi-person targeting (center face only — one subject at a time) |
| ❌ | Speaker diarization or voiceprints |
| ❌ | ANN index (linear scan is fine at hackathon scale) |
| ❌ | ExecuTorch runtime for Whisper (use the QNN binary directly) |
| ❌ | `FakeModelRunner` in the demo build (tests/CI only) |

---

## 20. Module responsibilities (per-file)

### 20.1 Flutter
- **`main.dart` / `app.dart`** — bootstrap, theming, routing across the four screens; runtime
  permissions; obtain `MemoryBackendApi`.
- **`core/models/*`** — plain Dart models mirroring the Pigeon DTOs.
- **`core/platform/*`** — Pigeon-generated bindings + thin wrapper/DI.
- **`features/recall/*`** — press-to-recall UI with the **center reticle overlay** (§14); renders
  Case A (name + suggestions + answers + evidence), Case B (unknown → enroll), Case C (location).
- **`features/timeline/*`** — paged `MemorySessionDto` list.
- **`features/enrollment/*`** — capture 5 **centered** frames + name/label.
- **`features/debug/*`** — lifecycle controls, `forceTick`, seed/clear, latency dashboard (§22 CP9).

### 20.2 Kotlin — bridge & service
- **`MainActivity.kt`** — registers the Pigeon host.
- **`bridge/MemoryBackendChannel.kt`** — implements the host API; pure delegation + mapping.
- **`bridge/DtoMappers.kt`** — Room entity ⇄ Pigeon DTO; never leaks `face_embedding`.
- **`service/MemoryForegroundService.kt`** — foreground service (camera|mic|location); warms all
  models once at start; owns `CaptureLoop`.
- **`service/CaptureLoop.kt`** — ~5 s tick (§9.1); frame → `FaceEmbeddingEngine.identify` →
  transcript → location → skip conditions (§10.4) → §10 insertion.

### 20.3 Kotlin — capture
- **`capture/CameraFrameProvider.kt`** — CameraX; latest frame as `Bitmap` with W×H.
- **`capture/TranscriptBuffer.kt`** — rolling 30 s window; `getTranscriptWindow()`.
- **`capture/LocationProvider.kt`** — fused location + manual label.
- **`capture/DemoContextProvider.kt`** — scripted frames/transcripts/locations for demo.

### 20.4 Kotlin — ai & identity
- **`ai/OnDeviceModelRunner.kt`** — interface (§8.3).
- **`ai/ExecuTorchModelRunner.kt`** — face `detect` + `extractLandmarks`(5pt) via the two `.pte`
  models; warm-up off UI; per-stage latency logs; never crash on load failure.
- **`ai/QnnModelRunner.kt`** — raw QNN context-binary runtime: `embedFace`(CavaFace `cavaface.bin`)
  and `transcribe`(Whisper `encoder.bin`+`decoder.bin`); `SpeechRecognizer` fallback for ASR.
- **`ai/GenieLlmRunner.kt`** — `generate` via the QAIRT Genie dialog API (Llama 3.2 1B bundle);
  template fallback on load/timeout failure.
- **`ai/ModelAssets.kt`** — resolves model paths under the device external files dir; reports which
  models are present so missing ones degrade gracefully.
- **`ai/FakeModelRunner.kt`** — deterministic stubs (tests/CI only).
- **`identity/FaceEmbeddingEngine.kt`** — center-crop selection + alignment + CavaFace + dot-product
  match + enrollment averaging (§4).
- **`identity/IdentityConfig.kt`** — constants (§3.3).

### 20.5 Kotlin — recall, data, enrollment
- **`recall/RecallEngine.kt`** — orchestrates §11–§12 + Case A/B/C.
- **`recall/RetrievalRanker.kt`** — SQL narrowing + recency sort (top 5).
- **`recall/QuestionSuggestionEngine.kt`** — template-first + optional LLM rewrite.
- **`recall/AnswerGenerator.kt`** — grounded answer + evidence.
- **`data/MemoryDatabase.kt`** — Room DB + version/migrations.
- **`data/PersonProfileEntity.kt` / `MemorySessionEntity.kt`** — exact schema (§5).
- **`data/PersonDao.kt`** — insert, `findAll()` (for the embedding scan), findById, update name/notes.
- **`data/SessionDao.kt`** — insert, findByPersonId, vicinity, findRecent, listPaged.
- **`data/MemoryRepository.kt`** — single entry point; owns §10 insertion logic.
- **`data/EmbeddingCodec.kt`** — `float32[]` ⇄ little-endian blob + L2-normalize (used for
  `face_embedding`).
- **`data/DemoSeeder.kt`** — demo people (512-d L2-normalized embeddings) + sessions with transcripts.
- **`enrollment/PersonEnrollmentManager.kt`** — capture 5 centered samples → `averageEmbeddings` →
  L2-normalize → store; set name/context.

---

## 21. Checkpoint 2.6 — data migration (v2 landmark → v4 CavaFace embedding)

The data layer currently uses the v2 `landmark_vector (float32[936])` schema. Migrate it to the v4
`face_embedding (float32[512])` schema. **This is the immediate next task.** No `identity/` folder
exists yet, so this is a pure data-layer refactor — no engine to delete.

### 21.1 Exact file-by-file changes
1. **`data/PersonProfileEntity.kt`**
   - Rename column `landmark_vector` → `face_embedding` (BLOB, non-null), now `float32[512]`.
   - Rename the Kotlin field `landmarkVector` → `faceEmbedding`; update `equals`/`hashCode`
     (`contentEquals`/`contentHashCode`) and the KDoc (drop "landmark geometry"; it is an ArcFace
     embedding).
2. **`data/LandmarkCodec.kt` → `data/EmbeddingCodec.kt`**
   - Rename the file/object; it is already a generic `float32[]` ⇄ blob + L2-normalize codec. Update
     the KDoc to reference `face_embedding` (512-d). Update all call sites.
3. **`data/DemoSeeder.kt`**
   - Seed each demo person with a deterministic **L2-normalized `float32[512]`** embedding (random or
     fixed seed) instead of a 936-d landmark vector. Keep 2–3 people + 5+ sessions with transcripts.
4. **`data/PersonDao.kt`**
   - Ensure `findAll()` returns all profiles for the in-memory embedding scan; rename any
     landmark-named params/queries; no vector SQL.
5. **`data/MemorySessionEntity.kt`**
   - No column change (already no embeddings). Update any stale "landmark"/v2 KDoc.
6. **`data/MemoryRepository.kt`**
   - Update field references `landmarkVector` → `faceEmbedding`; keep §10 insertion behavior.
7. **`data/MemoryDatabase.kt`**
   - Bump the Room schema version; use `fallbackToDestructiveMigration()` (no real user data).
8. **Pigeon** — **no change.** `PersonProfileDto` is already `{personId, name, contextNotes}`; do not
   regenerate unless other API edits are made.

### 21.2 Definition of done
Project compiles; `person_profile_table` has `face_embedding` (`float32[512]`) and **no**
`landmark_vector`; `seedDemoData()` populates the new schema with 512-d embeddings; `TimelineScreen`
shows rows; `clearAllData()` wipes; CP1 Pigeon round-trip still works.

---

## 22. Build checkpoints

Work sequentially; each is independently demoable. (CP1 ✅, CP2 ✅, CP2.5 ✅ → do **CP2.6** first,
then CP3 onward.)

### CHECKPOINT 1 — Project skeleton & Pigeon bridge — ✅ DONE
Flutter shell (4 screens) + Pigeon API + `MemoryBackendChannel` (canned) + `MainActivity` host.
**DoD (met):** `RecallScreen` calls `captureRecallContext()` → hardcoded DTO; app runs.

### CHECKPOINT 2 — Data layer — ✅ DONE
Room DB + entities + DAOs + repository + seeder + mappers.

### CHECKPOINT 2.5 — v1→v2 migration (landmark) — ✅ DONE (superseded by v4)
Schema moved to `landmark_vector float32[936]`; `EmbeddingCodec`→`LandmarkCodec`.

### CHECKPOINT 2.6 — v2→v4 migration (CavaFace embedding) — ⛔ NEXT
Apply §21. **DoD:** §21.2.

### CHECKPOINT 3 — model runners (ExecuTorch + QNN + Genie)
- ExecuTorch AAR in `android/app/libs/`; QAIRT 2.45 `.so` libs in `jniLibs/arm64-v8a/`.
- **`ExecuTorchModelRunner`**
  - `detectFaces()` → `List<FaceBox>` (bbox + confidence) from `mediapipe_face_detection.pte`.
  - `extractLandmarks()` → 5 key points (10 floats) from `mediapipe_face_landmark_detector.pte`.
- **`QnnModelRunner`** (raw QNN context binaries)
  - `embedFace()` → CavaFace `float32[512]` from `cavaface.bin` (logical input `float32[1,3,112,112]`
    in `[-1,1]`, CHW; quantized at the graph boundary), L2-norm.
  - `transcribe()` → Whisper `encoder.bin`+`decoder.bin`.
- **`GenieLlmRunner`**
  - `generate()` → Llama 3.2 1B via the QAIRT **Genie** dialog API (3-part Genie bundle).
- All warm up off UI on service start; latency logged (`face_detection`, `face_alignment`,
  `face_embedding`, `asr`, `llm_generation`). Any load failure: log + safe fallback — **never crash**.
- **DoD:** Logcat shows each model loaded at startup; camera at a face → bbox + 5 landmarks +
  512-d embedding within ~150 ms; no crash when any model is missing.
- **Status:** 🟡 code implemented (`ai/` runners + native bridge skeleton + Gradle/NDK wiring);
  on-device DoD pending (AAR + QAIRT libs + pushed models + S25). Deviations in §24.1.

### CHECKPOINT 4 — FaceEmbeddingEngine + center-crop selection
- `selectCenterFace()` — min Euclidean distance to frame center; null if > `MAX_CENTER_OFFSET_PX`.
- `alignFace()` — 5 landmarks → rotate/scale crop to 112×112.
- `identify()` — full pipeline → `MatchResult`.
- `averageEmbeddings()`, `cosineSimilarity()` (dot product of two `float32[512]`), `l2Normalize()`.
- Camera preview overlay: center reticle with state colors (grey/yellow/green/red).
- **DoD:** Multiple faces → only center face identified; edge face → ignored; enrolled centered face →
  green reticle + correct name; unknown centered face → red reticle + "Unknown";
  `MAX_CENTER_OFFSET_PX` tunable in one place.
- **Status:** 🟡 `FaceEmbeddingEngine` + `IdentityConfig` + `center_reticle.dart` implemented; geometry/
  matching DoD covered by JVM unit tests (not run here, no JDK). Live camera demo pending CP6/CP7 + S25.
  Deviations in §24.2.

### CHECKPOINT 5 — Whisper ASR runner (QNN)
- `QnnModelRunner` loads `whisper_encoder.bin` + `whisper_decoder.bin` via the QNN context-binary
  API; PCM16 → log-mel `float32[1,80,3000]`; `transcribe()`; `TranscriptBuffer` rolling 30 s with
  `getTranscriptWindow()`; encoder/decoder latency tracked; `SpeechRecognizer` fallback.
- **DoD:** Spoken sentence → correct transcript within ~2 s; fallback activates cleanly on binary
  load failure.
- **Status:** 🟡 `LogMelSpectrogram` (PCM16→80×3000) + `TranscriptBuffer` (rolling 30 s) +
  `SpeechRecognizerFallback` implemented; `QnnModelRunner.transcribe` wires mel→native. Front-end/buffer
  covered by JVM unit tests (not run here). Native decode + ~2 s DoD pending QAIRT + S25. See §24.3.

### CHECKPOINT 6 — Capture loop (full write path)
- `MemoryForegroundService` starts `CaptureLoop` (camera + mic + location). Tick ~5 s: frame →
  `FaceEmbeddingEngine.identify()` → transcript → location → skip conditions → insert Case A/B/C.
  `forceTick()` via Pigeon.
- **DoD:** After 60 s the DB has multiple rows; Cases A/B/C correct; `forceTick()` works;
  **bystander faces at frame edges do NOT trigger Case A/B** (center constraint confirmed).
- **Status:** 🟡 `MemoryForegroundService` + `CaptureLoop` + camera/location/audio providers +
  `SceneChangeDetector` + `CaptureSink` inserts implemented; channel drives the service; recall reads
  live `CaptureState`. §10 branching + §10.4 skip covered by JVM unit tests. On-device 60-s DoD needs
  the S25 camera; live A/B also needs QAIRT (CavaFace) + CP8. Deviations in §24.5.

### CHECKPOINT 7 — Llama LLM runner + recall pipeline (full read path)
- `generate()` confirmed (from CP3); `RecallEngine.captureRecallContext()` → `RecallContextDto`;
  `RetrievalRanker.retrieve()` (SQL person_id + 75 m vicinity, recency DESC, top 5);
  `QuestionSuggestionEngine.suggest()`; `AnswerGenerator.answer()` (grounded + evidence); >5 s
  timeout → `"This is {name}."`. `RecallScreen` renders Case A/B/C.
- **DoD (airplane mode):** Enroll teammate → store sessions via `forceTick()` → Recall on centered
  face → name + suggestions → tap question → answer with evidence.
- **Status:** 🟡 `RetrievalRanker` + `QuestionSuggestionEngine` + `AnswerGenerator` + `RecallEngine`
  implemented and wired into the channel; Flutter UI already renders A/B/C. Logic covered by JVM unit
  tests. Demoable now via `seedDemoData` (frame demo-fallback); live-face + grounded LLM need CP6/CP8 +
  Genie libs. Deviations in §24.4.

### CHECKPOINT 8 — Enrollment flow
- `EnrollmentScreen` with center reticle; capture exactly `ENROLLMENT_SAMPLES = 5` **centered**
  frames → 5 embeddings → `PersonEnrollmentManager.enroll()` averages + L2-normalizes + stores;
  confirmation with name; `enrollPerson()` via Pigeon.
- **DoD:** Enrollment captures 5 centered frames; reticle green when centered/ready; the person is
  recognized as Case A in future ticks.
- **Status:** 🟡 `PersonEnrollmentManager` + `enrollNewPerson` Pigeon API + EnrollmentScreen camera FAB
  + reticle overlay implemented; failure path unit-tested, averaging via CP4 tests. On-device 5-frame
  capture + Case-A recognition needs the S25 camera + QAIRT/CavaFace libs. Deviations in §24.6.

### CHECKPOINT 9 — Latency dashboard & demo polish
- Latency for all stages: `face_detection`, `face_alignment`, `face_embedding`, `identity_match`,
  `whisper_encoder`, `whisper_decoder`, `llm_generation`, `total_pipeline`.
- DebugScreen live table, color-coded (🟢 <100 ms, 🟡 100–500 ms, 🔴 >500 ms); shows
  `Center face sim: 0.87 → Sarah ✓`. `DemoContextProvider` + `DemoSeeder` for a reliable room demo.
  All error states handled (model failure, no center face, unknown face, LLM timeout, no transcript).
  Airplane mode verified — zero network calls.
- **DoD:** `seedDemoData()` once → service start → Recall on centered enrolled face → cue spoken →
  question answered with evidence → all airplane mode → latency dashboard live → no crash for 10 min.

---

## 23. Build & run

```bash
flutter pub run pigeon --input pigeons/memory_backend.dart   # after any API change
flutter pub get
flutter run                                                  # S25 Ultra, USB debugging
```
Grant on first launch: Camera, Microphone, Location, Notifications.

---

## 24. Open questions & tuning

| Parameter | Default | Notes |
| --- | --- | --- |
| `TAU_KNOWN` | 0.60 | Standard ArcFace cosine threshold; lower to ~0.55 if false negatives are high. |
| `MAX_CENTER_OFFSET_PX` | 120 px | ~25% of a 640-wide frame; tune to taste (tighter = more intentional). |
| Tick cadence | ~5 s | Tune for battery impact on the S25 Ultra. |
| `ENROLLMENT_SAMPLES` | 5 | Increase to 10 if matching accuracy is insufficient. |
| `VICINITY_RADIUS_M` | 75 m | Adjust if location retrieval is too narrow/broad. |
| Whisper QNN SDK | — | Verify `metadata.json` tensor specs on device. |
| Llama latency | — | If >8 s, reduce `max_tokens` or use template-only mode. |
| CavaFace warm-up | — | ~62 MB takes a few seconds cold — load at service start, keep warm. |
| Meta Glasses relay | — | Bluetooth frame-relay architecture TBD when the device is confirmed. |

### 24.1 CP3 deviations & status (contract §"if you deviate, note it here")
Implemented the three runner backends + interface + composite. Deviations from the literal plan:
1. **Reflective ExecuTorch** (`ai/ExecuTorchBackend.kt`) instead of a direct AAR `import`, so the
   whole `ai/` module **compiles with zero external artifacts** and degrades gracefully when
   `app/libs/*.aar` is absent. Switch to direct imports later if preferred — no behavior change.
2. **`CompositeModelRunner` added** (not named in §8.2) — `FaceEmbeddingEngine(runner:
   OnDeviceModelRunner)` (§4) needs one object exposing all six methods; the composite fans out to the
   three backends. The backend runners deliberately do **not** implement `OnDeviceModelRunner`.
3. **Native QNN/Genie bridge is a skeleton** (`cpp/synapse_inference.cpp`): lifecycle wired against
   the real API; graph I/O binding (tensor names/quant params) + Whisper decode are `TODO`/CP5.
4. **Detection/landmark decode constants are unverified** (`ExecuTorchModelRunner.DetectCfg`/
   `LandmarkCfg`: input size, anchors, FaceMesh indices) — confirm against the real `.pte` I/O
   on-device, same open item as the CavaFace I/O caveat (§2.1). Safe-empty fallback until then.
5. **Warm-up temporarily in `MemoryBackendChannel`** (off the UI thread) so CP3 is demoable before the
   service exists; **moves to `MemoryForegroundService` in CP6** (§20.2).
6. **Native build is Gradle-guarded** behind `qnnSdkRoot`/`QNN_SDK_ROOT`; `jniLibs/*.so` + the AAR are
   per-dev and gitignored (§16). `scripts/push_models.sh` deploys weights to the device.

**Build/DoD status:** code is written to compile cleanly and never crash on missing models, but the
**build + on-device DoD were NOT verified here** — this environment has no JDK/Android SDK/`adb`/device
and none of the AAR/QAIRT libs/model weights. ExecuTorch is now pulled from Maven
(`org.pytorch:executorch-android:1.3.1`), so only the QAIRT `.so` libs + model weights remain to add.
Verify on the S25: set `qnnSdkRoot`, copy QAIRT `.so` libs to `jniLibs/arm64-v8a/`, run
`scripts/push_models.sh`, then `flutter run` and watch logcat (`ModelAssets`, `MemoryLatency`).

### 24.2 CP4 deviations & status
`identity/FaceEmbeddingEngine` + `identity/IdentityConfig` + the Flutter `center_reticle.dart`
implemented. Deviations:
1. §4 geometry helpers are **`internal`** (not `private`) so CP4 unit tests assert center-selection /
   cosine / averaging (`src/test/.../FaceEmbeddingEngineTest.kt`).
2. The §4 `preprocess(...)` step is **not** duplicated in the engine — `QnnModelRunner.embedFace`
   applies the §2.1 CavaFace preprocessing to the 112×112 bitmap from `alignFace`.
3. `alignFace` uses a **2-point eye** similarity transform onto the ArcFace template (§3.7); upgrade to
   a full 5-point umeyama fit later if alignment accuracy needs it.
4. `MatchResult.personId = -1` denotes an unknown face (Case B); `name = null`.
5. The reticle is a **standalone widget**; live wiring to the camera + `identify()` (green+name / red
   over the preview) lands with the capture/recall path (CP6/CP7).

**Build/DoD status:** the **geometry/matching DoD is covered by passing-by-construction JVM unit
tests** (center face chosen, edge/small ignored, cosine/averaging) — but they were **not executed
here** (no JDK). Run `./gradlew :app:testDebugUnitTest` on a machine with the Android toolchain. The
live camera demo (green+name / red) needs CP6/CP7 + the S25.

### 24.3 CP5 deviations & status
Whisper ASR path implemented on **`QnnModelRunner`** (v4.1 rename of `WhisperQnnRunner`), plus the
front-end + buffer. Deviations:
1. `ai/LogMelSpectrogram.kt` is a faithful Kotlin port of `whisper.audio.log_mel_spectrogram`
   (reflect-pad STFT n_fft=400/hop=160, Slaney mel, log10 + `(max(x,max-8)+4)/4`). ⚠️ **Numeric parity
   with the Python reference is unverified here** — validate against a known clip before trusting
   transcripts.
2. `capture/TranscriptBuffer.kt` holds the rolling 30 s PCM ring + a transcript cache
   (`getTranscriptWindow()` returns the cached text; `window()` returns the audio).
3. `capture/SpeechRecognizerFallback.kt` wraps Android `SpeechRecognizer`. It recognizes from the
   **live mic**, not a PCM buffer, so the capture loop (CP6) starts it when
   `QnnModelRunner.isWhisperAvailable()` is false; `transcribe()` itself returns "" in that case.
4. Encoder/decoder **latency split** runs inside native; CP5 records overall `asr` latency.
   `whisper_encoder`/`whisper_decoder` stage constants exist for native to report later.
5. The native `whisperTranscribe` (encoder→decoder→detokenize over the 80×3000 features) remains the
   QNN skeleton's `TODO`, gated on the QAIRT libs.

**Build/DoD status:** front-end + buffer have **JVM unit tests** (mel shape 80×3000 + normalization;
ring rolling) — **not run here** (no JDK). The "~2 s transcript" DoD needs the native decode + QAIRT
libs + S25; the `SpeechRecognizer` fallback path needs the device. Logic compiles + degrades
gracefully (no Whisper ⇒ "").

### 24.4 CP7 deviations & status
Recall read path implemented: `recall/{RetrievalRanker, QuestionSuggestionEngine, AnswerGenerator,
RecallEngine}` wired into `MemoryBackendChannel`. The Flutter `RecallScreen`/controller already render
Cases A/B/C + suggestions + answer + evidence (built in CP1), so no Dart changes were needed.
Deviations:
1. **CP7 built before CP6/CP8.** `RecallEngine` takes `frameProvider`/`locationProvider` (default
   null). Until CP6 wires the camera, `captureRecallContext` falls back to the **most-recent seeded
   session** so the path is demoable via `seedDemoData`. The real identify path activates as soon as
   CP6 passes a frame provider — no API change.
2. **Sync Pigeon bridge.** `captureRecallContext/getSuggestions/answerQuestion` are synchronous Pigeon
   methods; the suspend recall work runs via `runBlocking(Dispatchers.IO)`. DB is fast and the LLM is
   time-boxed (5 s, currently instant fallback). Move these to `@async` (Pigeon regen) when the live
   Genie LLM is wired, to avoid blocking the platform thread.
3. **Vicinity** uses the existing `vicinity`/added `vicinityNoPerson` box queries (label OR lat/lng
   bounding box from `VICINITY_RADIUS_M`); no haversine refinement (box is adequate at 75 m).
4. **Suggestions** are template-only with deterministic ids; LLM rewrite deferred (no topic invention,
   §12.3). Topic = longest non-stopword token from the latest transcript.
5. **Answer** runs `generate` with a 5 s `withTimeoutOrNull`; empty rows → "I don't have a memory of
   that."; timeout/unavailable → `"This is {name}."` (§7.3). Evidence always attached.

**Build/DoD status:** retrieval/suggestion/answer logic covered by **JVM unit tests**
(`RetrievalRankerTest`, `QuestionSuggestionEngineTest`, `AnswerGeneratorTest`). The full airplane-mode
DoD (enroll → Recall on a centered face → name → answer) needs CP6 (camera) + CP8 (enrollment); today
it is demoable end-to-end via `seedDemoData` → Recall (demo fallback) → suggestions → answer +
evidence. Grounded LLM phrasing awaits the Genie libs (template fallback until then).

### 24.5 CP6 deviations & status
Capture write path implemented: `MemoryForegroundService` (LifecycleService) + `CaptureLoop` +
`CameraFrameProvider` (CameraX) + `LocationProvider` (fused) + `AudioCaptureProvider` (AudioRecord) +
`SceneChangeDetector`, with `MemoryRepository` implementing the new `CaptureSink` (§10 A/B/C inserts).
`MemoryBackendChannel.startCapture/stopCapture/forceTick` now drive the service, and the recall engine
reads live frame/location from `CaptureState` (so CP7's demo-fallback is now superseded when the
service runs). Deviations:
1. **`CaptureState` singleton** decouples the camera owner (service) from the recall consumer
   (channel) — frame/location are shared via it rather than passed across components.
2. **`ai/ModelRuntime` singleton** — service + channel share one `CompositeModelRunner` (warmed once),
   so warm-up effectively moved to the service per §20.2 (the channel no longer warms explicitly;
   runners also lazy-warm on first use).
3. **`MatchResult` gained `embedding`** (the L2-normalized query vector) so Case B can persist the new
   profile (§10.B). Backward-compatible (default empty).
4. **`CaptureLoop` is generic over the frame type** so its branch/skip logic is unit-tested on the JVM
   without an Android `Bitmap`; the service instantiates `CaptureLoop<Bitmap>`.
5. **Permissions requested natively in `MainActivity`** (camera/mic/location/notifications) instead of
   via a Flutter plugin (§17 says "Flutter requests"); the service re-checks and only starts providers
   / declares FGS types it holds permission for (Android 14 requirement).
6. **Camera frame rotation** is not applied (uses `ImageProxy.toBitmap()` sensor orientation) — fine
   for center-crop selection; refine if alignment needs it.

**Build/DoD status:** §10 case-branching + §10.4 skip conditions covered by **JVM unit tests**
(`CaptureLoopTest`, `SceneChangeDetectorTest`). The on-device 60-s DoD (all 3 cases; bystander/edge
faces rejected) needs the **camera on the S25**; note that until the **QAIRT/CavaFace libs** land
`embedFace` returns empty ⇒ `identify` yields Case C (no A/B), so live A/B verification is gated on the
QNN libs **and** CP8 enrollment. Case C capture (scene change) works on-device today.

### 24.6 CP8 deviations & status
Proactive enrollment implemented: `enrollment/PersonEnrollmentManager` captures
`ENROLLMENT_SAMPLES = 5` centered-face embeddings from the live camera, averages + L2-normalizes
(`FaceEmbeddingEngine.averageEmbeddings`), and stores a new named profile. New Pigeon method
`enrollNewPerson(name, contextNotes) → EnrollResultDto` (regenerated). The Flutter `EnrollmentScreen`
gains an **Enroll (camera)** FAB + name dialog + a center-reticle capture overlay; the existing
list/name path (Case B) is unchanged. Deviations:
1. **New Pigeon API** (`enrollNewPerson` + `EnrollResultDto`) — Pigeon regenerated (both generated
   files). The plan's `enrollPerson(personId,…)` stays for Case-B naming; proactive capture-enroll
   needed a camera-side trigger.
2. **No live camera preview in Flutter** — frames live in the service/`CaptureState`, not surfaced to a
   Flutter texture. The reticle overlay shows capture state as guidance; a real preview (PlatformView)
   is out of CP8 scope.
3. **Enrollment auto-starts capture** (`controller.enrollNew` calls `startCapture` first) so frames are
   flowing; it then collects 5 centered samples (≈ up to 10 s) and stores.
4. Enrollment reuses `identify(frame, emptyList())` purely to obtain the centered face's embedding.

**Build/DoD status:** the failure path (no centered frames → no insert) is covered by a **JVM unit
test** (`PersonEnrollmentManagerTest`); averaging/L2-norm is covered by CP4 tests. The full on-device
DoD (capture 5 centered frames; reticle green when ready; person later recognized as **Case A**) needs
the **S25 camera + QAIRT/CavaFace libs** — without CavaFace, `embedFace` is empty so enrollment reports
"couldn't capture" and later recognition can't discriminate faces. Logic compiles + degrades
gracefully.

### 24.7 On-device finding — ExecuTorch MediaPipe `.pte` aborts on guessed input (CP6 follow-up)
First on-device run (S25) surfaced a **native `SIGABRT` in `libexecutorch.so`** during `Module.forward`,
from `ExecuTorchModelRunner.detectFaces` on every capture tick (abort message `'ptr'`). The MediaPipe
detection/landmark `.pte` reject the **guessed** input tensor (`float32[1,3,256,256]`, `[-1,1]`; see the
unverified `DetectCfg`/`LandmarkCfg`, §24.1). A native abort **cannot be caught** by Kotlin try/catch,
so it killed the process → the foreground service restarted (START_STICKY) → crash loop (cycling PIDs).
**Fix:** gated ExecuTorch inference behind `ExecuTorchModelRunner.INFERENCE_ENABLED = false`
(`detectFaces`/`extractLandmarks` return empty without calling `forward`). The models still **load**
(proven on-device), and capture now runs stably → Case C rows accumulate; recall (seeded) + enrollment
(graceful) work. **To re-enable:** confirm the real input shape/dtype/normalization + output decode for
the delivered MediaPipe exports (from the model authors or by introspecting the `.pte`), set the decode
constants, then flip the flag. Identity (Case A/B) remains additionally gated on the QAIRT/CavaFace libs.

### 24.8 v4.2 — Google on-device backend + demo polish (post-hackathon)
Without access to the S25 / QAIRT artifacts, the pipeline was made to run live on a **Pixel 10**
(Tensor G5) by adding a second backend behind the unchanged `OnDeviceModelRunner` contract.
Deviations / additions:
1. **Backend selection** (`ai/ModelRuntime`): `CompositeModelRunner` (Qualcomm) only if
   `NativeInference.loaded` **and** `cavaface.bin` is present; otherwise `GoogleModelRunner`.
2. **`GoogleModelRunner`:** ML Kit face detection (bundled; returns the 5 alignment points on each
   `FaceBox`, so no separate landmark call) · **FaceNet-512** (`assets/litert/facenet_512.tflite`,
   160×160 per-image-standardized, bbox crop without eye warp) on LiteRT · Gemini Nano via the ML Kit
   GenAI Prompt API · speech from Android's live on-device `SpeechRecognizer`
   (`capture/LiveSpeechTranscriber`, since `SpeechRecognizer` owns the mic and never exposes PCM).
3. **Contract additions** on `OnDeviceModelRunner` (all with defaults, so the Qualcomm path is
   unchanged): `faceAlignment` (ArcFace-112 eye template vs FaceNet-160 plain crop), `matchThreshold`
   (`TAU_KNOWN` 0.60 for CavaFace; **0.40** for FaceNet ≈ L2 1.1), `generateChat(system, user)` (default
   wraps the §7 Llama 3 template), `transcribesAudio`, `backendName`.
4. **Geometry in fractions:** `MAX_CENTER_OFFSET_FRAC = 0.25`, `MIN_FACE_SIZE_FRAC = 0.12` of the frame's
   short side (identical to the old 120 px at 640×480) because frames are now ~720p and **rotated
   upright** (the S25-era code used sensor-orientation frames, §24.5.6).
5. **Sessions = conversations** (`MemoryRepository.insertOrMerge`): ticks ≤ 60 s apart extend one
   session; a no-person session is claimed by the person identified next, and speech while the face is
   briefly out of frame stays with them. Fixes speech landing in a "no person" row when it preceded the
   face by tens of seconds (observed on-device).
6. **Speaker-neutral prompts:** transcripts aren't diarized (§19), so cue/answer prompts forbid
   attributing statements to either person ("you talked about…"); topic suggestion is "What did {name}
   and I talk about regarding {topic}?". Recognizer re-emissions of the previous phrase are stripped
   (`capture/TranscriptMerge`, unit-tested).
7. **Recall cue (§7.1) implemented** and added to `RecallContextDto.recallCue`; cues and answers are
   spoken with Android `TextToSpeech` (`recall/RecallSpeaker`).
8. **Async recall bridge:** `captureRecallContext/getSuggestions/answerQuestion` are now `@async`
   (resolves §24.4.2 — no `runBlocking` on the platform thread now that a real LLM runs).
9. **Live preview** (`getLivePreview` → `LivePreviewDto`): the latest frame as JPEG + centered-face box,
   known/unknown, name, similarity; the Recall screen draws the real targeting circle (§14). Camera runs
   ~12 fps while the preview polls, 4 fps otherwise.
10. Service running state now comes from `MemoryForegroundService.running` (was a stale channel flag).

**Verified on-device (Pixel 10, Flutter 3.50.1):** enrollment, Cases A/B/C, edge-face rejection,
live transcripts, merged conversation entries, Gemini Nano cue + answers, TTS, live preview.

---

## 25. Version decision summary

| Decision | v1 | v2 | v3 | v4 |
| --- | --- | --- | --- | --- |
| Face identity | CavaFace float | Landmarks | CavaFace w8a16 | **CavaFace w8a16** |
| Face selection | Largest face | Landmark match | Largest face | **Center face** |
| Uncontrolled accuracy | ~97% | ~64% | ~99.5% | **~99.5%** |
| Consent UX | None | N/A | ConsentScreen | **None** |
| Scene embedding | MobileCLIP | None | None | **None** |
| Retrieval | Vector | SQL | SQL | **SQL** |
| Whisper | Not set | w8a16 QNN | w8a16 QNN | **w8a16 QNN** |
| MediaPipe role | Detection+identity | Detection+identity | Detection+alignment | **Detection+alignment** |

---

*This document is the contract. Sub-agents: if you must deviate, update this file in the same change
and note it in §24.*
