package social.tbone.toolbox

/** State machine for a partially initialized recorder and its temporary file.
 * Operations are invoked under VoiceRecordingGate's lock. Release and file
 * removal failures stay retryable without stopping an already-stopped recorder. */
internal class RecordingCleanup(
    private val stop: () -> Unit,
    private val release: () -> Unit,
    private val delete: () -> Unit,
) {
    private var started = false
    private var stopAttempted = false
    private var finalized = false
    private var released = false

    fun markStarted() { started = true }

    /** True only when a complete recording may be kept. */
    fun close(discard: Boolean): Boolean {
        if (!released) {
            if (started && !stopAttempted) {
                stopAttempted = true
                finalized = try { stop(); true } catch (_: Exception) { false }
            }
            release()
            released = true
        }
        if (discard || !finalized) delete()
        return !discard && finalized
    }
}
