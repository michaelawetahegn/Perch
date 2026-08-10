package dev.mkiros.perch

import java.io.PrintWriter
import java.io.StringWriter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/** TEMPORARY probe for issue #1: shouts about every uncaught coroutine exception. */
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
