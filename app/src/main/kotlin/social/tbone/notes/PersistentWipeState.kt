package social.tbone.notes

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tiny durable intent marker; it contains no PINs or user content. */
interface WipeStateStore {
    fun read(): Set<NoteWriteCoordinator.WipeKind>
    fun write(pending: Set<NoteWriteCoordinator.WipeKind>): Boolean
}

@Singleton
class PersistentWipeState @Inject constructor(@ApplicationContext context: Context) : WipeStateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): Set<NoteWriteCoordinator.WipeKind> = try {
        val names = prefs.getStringSet(KEY, emptySet()).orEmpty()
        if (names.any { it !in NoteWriteCoordinator.WipeKind.entries.map { kind -> kind.name } }) {
            setOf(NoteWriteCoordinator.WipeKind.APP) // Unknown marker: fail closed.
        } else names.mapTo(mutableSetOf()) { NoteWriteCoordinator.WipeKind.valueOf(it) }
    } catch (_: Exception) {
        setOf(NoteWriteCoordinator.WipeKind.APP)
    }

    override fun write(pending: Set<NoteWriteCoordinator.WipeKind>): Boolean = try {
        val editor = prefs.edit()
        if (pending.isEmpty()) editor.remove(KEY) else editor.putStringSet(KEY, pending.mapTo(mutableSetOf()) { it.name })
        editor.commit()
    } catch (_: Exception) { false }

    companion object {
        const val PREFS_NAME = "tbone_pending_wipe"
        private const val KEY = "pending_kinds"
    }
}

internal class InMemoryWipeState : WipeStateStore {
    private var value = emptySet<NoteWriteCoordinator.WipeKind>()
    override fun read() = value.toSet()
    override fun write(pending: Set<NoteWriteCoordinator.WipeKind>): Boolean { value = pending.toSet(); return true }
}
