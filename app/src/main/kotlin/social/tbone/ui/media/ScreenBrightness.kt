package social.tbone.ui.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide background brightness, applied as an overlay at the activity root.
 *
 * 0.5 = normal (no overlay). Below 0.5 darkens the whole app (black overlay);
 * above 0.5 lightens it (white overlay). The overlay is drawn above all
 * content but does not intercept touches.
 */
object ScreenBrightness {
    const val NORMAL = 0.5f
    const val MIN = 0.15f
    const val MAX = 0.85f

    private val _level = MutableStateFlow(NORMAL)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun set(level: Float) {
        _level.value = level.coerceIn(MIN, MAX)
    }
}
