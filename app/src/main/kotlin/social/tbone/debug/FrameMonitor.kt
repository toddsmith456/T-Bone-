package social.tbone.debug

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import timber.log.Timber

/**
 * Logs per-frame timing breakdown so scroll jank can be measured directly instead
 * of guessed at. Uses [Window.addOnFrameMetricsAvailableListener] (API 24+), which
 * reports each frame's measure/layout, draw, and total durations.
 *
 * Every [WINDOW_MS] it dumps: frame count, how many exceeded the 16.7ms budget,
 * the worst total, and where the slow frames spent their time. A frame that blows
 * the budget in LAYOUT means deep/expensive layout (measure pass); in DRAW means
 * expensive draw (Text/Canvas/overdraw).
 */
object FrameMonitor {

    private const val WINDOW_MS = 5_000L
    private const val JANK_BUDGET_NS = 16_700_000L // 60fps frame budget
    private const val MS = 1_000_000.0

    private var thread: HandlerThread? = null

    private var frames = 0
    private var janky = 0
    private var worstTotalNs = 0L
    private var worstBreakdown = ""
    private var windowStart = 0L

    fun start(activity: Activity) {
        val handlerThread = HandlerThread("FrameMonitor").apply { start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        windowStart = System.currentTimeMillis()

        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)

            synchronized(this) {
                frames++
                if (total > JANK_BUDGET_NS) {
                    janky++
                    if (total > worstTotalNs) {
                        worstTotalNs = total
                        worstBreakdown = breakdown(metrics)
                    }
                }
                if (System.currentTimeMillis() - windowStart >= WINDOW_MS) dumpAndReset()
            }
        }, handler)

        Timber.d("FrameMonitor started")
    }

    /**
     * Full phase breakdown of a single frame. "unknownDelay" is time the frame
     * waited because the main thread was still busy with prior work (the smoking
     * gun for a long synchronous task); "layout"/"draw" are the Compose passes;
     * "sync"/"cmd"/"swap"/"gpu" are the render/GPU handoff.
     */
    private fun breakdown(m: FrameMetrics): String {
        fun ms(id: Int) = m.getMetric(id) / MS
        return "unknownDelay=%.1f input=%.1f anim=%.1f layout=%.1f draw=%.1f sync=%.1f cmd=%.1f swap=%.1f".format(
            ms(FrameMetrics.UNKNOWN_DELAY_DURATION),
            ms(FrameMetrics.INPUT_HANDLING_DURATION),
            ms(FrameMetrics.ANIMATION_DURATION),
            ms(FrameMetrics.LAYOUT_MEASURE_DURATION),
            ms(FrameMetrics.DRAW_DURATION),
            ms(FrameMetrics.SYNC_DURATION),
            ms(FrameMetrics.COMMAND_ISSUE_DURATION),
            ms(FrameMetrics.SWAP_BUFFERS_DURATION),
        )
    }

    private fun dumpAndReset() {
        if (frames > 0 && janky > 0) {
            Timber.d(
                "FrameStats: %d frames, %d janky (%.0f%%), worst=%.1fms [%s]".format(
                    frames, janky, janky * 100.0 / frames, worstTotalNs / MS, worstBreakdown,
                ),
            )
        }
        frames = 0; janky = 0
        worstTotalNs = 0; worstBreakdown = ""
        windowStart = System.currentTimeMillis()
    }
}
