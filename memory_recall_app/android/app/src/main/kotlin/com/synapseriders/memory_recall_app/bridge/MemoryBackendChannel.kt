package com.synapseriders.memory_recall_app.bridge

/**
 * Milestone 1 host implementation of the Pigeon [MemoryBackendApi].
 *
 * For the skeleton milestone this returns canned/stubbed data so the Flutter UI and the
 * Flutter<->Kotlin round-trip can be exercised end to end without the data layer, capture
 * loop, or AI runner (those arrive in later milestones — IMPLEMENTATION_PLAN.md Section 16).
 *
 * Per Section 13.2 the real channel contains no business logic beyond delegation + mapping;
 * here the "delegate" is an in-memory stub so the contract is observable today.
 */
class MemoryBackendChannel : MemoryBackendApi {

    // Minimal in-memory state so toggles/seed/clear visibly change ServiceStateDto.
    private var running: Boolean = false
    private var seeded: Boolean = false

    private val cannedPeople: List<PersonProfileDto> = listOf(
        PersonProfileDto(
            personId = 1L,
            name = "Priya",
            referenceFaceImagePath = null,
            contextNotes = "Met at the office cafe; talked about her Kyoto trip.",
        ),
        PersonProfileDto(
            personId = 2L,
            name = "Marco",
            referenceFaceImagePath = null,
            contextNotes = "Climbing gym regular.",
        ),
    )

    private val cannedSessions: List<MemorySessionDto> = listOf(
        MemorySessionDto(
            sessionId = 101L,
            personId = 1L,
            personName = "Priya",
            locationLabel = "office cafe",
            timestampMs = 1_719_300_000_000L,
            frameImagePath = "/stub/frames/priya_cafe.jpg",
            transcript = "Priya: Kyoto was incredible, the temples in Arashiyama were the highlight.",
        ),
        MemorySessionDto(
            sessionId = 102L,
            personId = 2L,
            personName = "Marco",
            locationLabel = "climbing gym",
            timestampMs = 1_719_200_000_000L,
            frameImagePath = "/stub/frames/marco_gym.jpg",
            transcript = "Marco: I finally sent that V6 on the overhang wall.",
        ),
        MemorySessionDto(
            sessionId = 103L,
            personId = null, // Case C: no person in frame
            personName = null,
            locationLabel = "home desk",
            timestampMs = 1_719_100_000_000L,
            frameImagePath = "/stub/frames/desk_whiteboard.jpg",
            transcript = "Reminder note on the whiteboard about the Friday demo.",
        ),
    )

    // --- Service lifecycle ---------------------------------------------------

    override fun startCapture() {
        running = true
    }

    override fun stopCapture() {
        running = false
    }

    override fun getServiceState(): ServiceStateDto = ServiceStateDto(
        running = running,
        sessionCount = if (seeded) cannedSessions.size.toLong() else 0L,
        personCount = if (seeded) cannedPeople.size.toLong() else 0L,
    )

    override fun forceTick() {
        // Stub: a real tick (Milestone 3) would persist a session here.
    }

    // --- Recall flow ---------------------------------------------------------

    override fun captureRecallContext(): RecallContextDto = RecallContextDto(
        personId = 1L,
        personName = "Priya",
        personKnown = true, // Case A
        personPresent = true,
        locationLabel = "office cafe",
        latitude = 37.4219983,
        longitude = -122.084,
        currentTranscript = null,
        frameThumbPath = "/stub/frames/priya_cafe.jpg",
    )

    override fun getSuggestions(ctx: RecallContextDto): List<SuggestionDto> = listOf(
        SuggestionDto(id = "q1", questionText = "What did Priya say about Kyoto?"),
        SuggestionDto(id = "q2", questionText = "When did I last see Priya?"),
        SuggestionDto(id = "q3", questionText = "Where did I meet Priya?"),
    )

    override fun answerQuestion(
        ctx: RecallContextDto,
        suggestionId: String,
        freeText: String?,
    ): RecallAnswerDto = RecallAnswerDto(
        answerText = "You last spoke with Priya at the office cafe about her Kyoto trip; " +
            "she said the temples in Arashiyama were the highlight.",
        evidence = listOf(
            EvidenceDto(
                sessionId = 101L,
                timestampMs = 1_719_300_000_000L,
                locationLabel = "office cafe",
                frameThumbPath = "/stub/frames/priya_cafe.jpg",
                transcriptSnippet = "Kyoto was incredible, the temples in Arashiyama were the highlight.",
            ),
        ),
    )

    // --- Enrollment ----------------------------------------------------------

    override fun enrollPerson(personId: Long, name: String, contextNotes: String?) {
        // Stub: Milestone 7 persists name/context onto the profile.
    }

    // --- Timeline / debug ----------------------------------------------------

    override fun listSessions(limit: Long, offset: Long): List<MemorySessionDto> {
        if (!seeded) return emptyList()
        val from = offset.toInt().coerceIn(0, cannedSessions.size)
        val to = (from + limit.toInt()).coerceIn(from, cannedSessions.size)
        return cannedSessions.subList(from, to)
    }

    override fun listPeople(): List<PersonProfileDto> =
        if (seeded) cannedPeople else emptyList()

    override fun seedDemoData() {
        seeded = true
    }

    override fun clearAllData() {
        seeded = false
    }
}
