package dev.mkiros.perch.support

/**
 * The one wall-clock poll loop the whole test suite waits with.
 *
 * A JVM test here is nearly always waiting on a *real* background thread — Room's query
 * executor, DataStore's, OkHttp's — and the tools that look like they would wait for one
 * do not. `ComposeTestRule.waitUntil` advances Compose's **virtual** clock, so its entire
 * timeout can burn in microseconds without that thread ever being scheduled; `waitForIdle`
 * flushes the looper, which is not where the work is either. Only sleeping in wall-clock
 * time gives the other thread somewhere to run.
 *
 * Polls [predicate] every [POLL_MS] until it holds, and throws naming [what] if it has not
 * held within [timeoutMs]. [what] is a noun phrase — it is read as "timed out waiting for
 * <what>", so it should say what the test wanted, not what it did.
 *
 * The Compose-flavoured twin, which flushes the composition around each poll, is
 * `ComposeTestRule.awaitInRealTime` in `ui/screenshot/ScreenshotSupport.kt`.
 */
fun awaitInRealTime(
    what: String,
    timeoutMs: Long = AWAIT_TIMEOUT_MS,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(POLL_MS)
    }
    throw AssertionError("timed out waiting for $what")
}

/**
 * Long enough that a loaded machine running the full suite does not fail a test that was
 * only slow. It bounds a *failure*, so it costs nothing when the wait succeeds; the copies
 * this replaced had diverged over 5/10/20 s for no stated reason. Pass a longer one
 * explicitly, with a comment, if a test genuinely needs it.
 */
const val AWAIT_TIMEOUT_MS = 20_000L

private const val POLL_MS = 10L
