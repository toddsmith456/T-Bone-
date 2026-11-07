package social.tbone.webxdc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks overlapping downloads, including multiple requests for the same key. */
internal class XdcBusyTracker {
    private val lock = Any()
    private val active = mutableMapOf<String, MutableSet<Any>>()
    private val mutableKeys = MutableStateFlow<Set<String>>(emptySet())
    val keys = mutableKeys.asStateFlow()

    fun start(key: String): Any = synchronized(lock) {
        val token = Any()
        active.getOrPut(key) { mutableSetOf() }.add(token)
        mutableKeys.value = active.keys.toSet()
        token
    }
    fun finish(key: String, token: Any) = synchronized(lock) {
        val tokens = active[key]
        tokens?.remove(token)
        if (tokens?.isEmpty() == true) active.remove(key)
        mutableKeys.value = active.keys.toSet()
    }
    fun clear() = synchronized(lock) {
        active.clear()
        mutableKeys.value = emptySet()
    }
}
