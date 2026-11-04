package app.kamayoq.android

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Where an element of the open book sits on screen, in pixels. Waits up to 10 s for the section to be
 * ready, and says what it saw when the element never shows up.
 */
fun WebView.screenPointOf(selector: String): Pair<Float, Float> {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun evaluate(script: String): String {
        val answer = CompletableFuture<String>()
        instrumentation.runOnMainSync { evaluateJavascript(script, answer::complete) }
        return answer.get(30, TimeUnit.SECONDS).trim('"')
    }
    val script = """
        (() => {
            try {
                const contents = document.querySelector('foliate-view').renderer.getContents()
                const found = contents.map(c => c.doc.querySelector(${quoted(selector)})).find(Boolean)
                if (!found) return 'missing: ' + contents.map(c => c.index + ' ' + c.doc.body.innerHTML.slice(0, 400)).join(' | ')
                const frame = found.ownerDocument.defaultView.frameElement.getBoundingClientRect()
                const target = found.getClientRects()[0]
                return [frame.left + target.left + Math.min(20, target.width / 2), frame.top + target.top + target.height / 2].join(',')
            } catch (e) {
                return 'error: ' + e
            }
        })()
    """.trimIndent()
    var answer = ""
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
        answer = evaluate(script)
        val point = answer.split(',').mapNotNull { it.toFloatOrNull() }
        if (point.size == 2) {
            // Page pixels are CSS pixels; with the reader's viewport, one is one dp.
            val density = resources.displayMetrics.density
            val location = IntArray(2)
            instrumentation.runOnMainSync { getLocationOnScreen(location) }
            return location[0] + point[0] * density to location[1] + point[1] * density
        }
        SystemClock.sleep(500)
    }
    error("Could not place a touch on $selector: $answer")
}

/** A real finger: down and up at [point], held for [holdMs]. */
fun touchScreen(point: Pair<Float, Float>, holdMs: Long = 0) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val (x, y) = point
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
    if (holdMs > 0) SystemClock.sleep(holdMs)
    instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
}

/** A real finger dragged from [from] to [to] in [steps] moves over [durationMs]. */
fun swipeScreen(from: Pair<Float, Float>, to: Pair<Float, Float>, steps: Int = 12, durationMs: Long = 300) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val down = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, from.first, from.second, 0))
    for (step in 1..steps) {
        val x = from.first + (to.first - from.first) * step / steps
        val y = from.second + (to.second - from.second) * step / steps
        SystemClock.sleep(durationMs / steps)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0))
    }
    instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, to.first, to.second, 0))
}

private fun quoted(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
