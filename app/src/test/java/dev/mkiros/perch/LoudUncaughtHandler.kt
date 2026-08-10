package dev.mkiros.perch

import java.io.PrintWriter
import java.io.StringWriter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Shouts about every uncaught coroutine exception, with its stack.
 *
 * Kept deliberately rather than deleted with issue #1's fix. The leak that fix removes is
 * proven (`ProcessLifecycleTest`), but issue #1's *reported* signature —
 * `UncaughtExceptionsBeforeTest`, a leaked coroutine billed to whichever test ran next —
 * never reproduced in the runs that hunted it, so the link between that leak and that
 * failure is inference. If the signature ever comes back, this prints the real thrower's
 * stack on the first run instead of costing another session's worth of suite runs to see
 * it. It costs nothing while nothing throws: it only ever prints.
 */
class LoudUncaughtHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val sw = StringWriter()
        exception.printStackTrace(PrintWriter(sw))
        println(
            "PROBE-UNCAUGHT thread=${Thread.currentThread().name} ctx=$context\n$sw",
        )
    }
}
