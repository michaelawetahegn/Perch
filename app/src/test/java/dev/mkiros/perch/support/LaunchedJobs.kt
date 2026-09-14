package dev.mkiros.perch.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job

/**
 * The jobs a scope gained after this was made — the ones an action under test launched.
 *
 * A test cannot wait on *every* child of a ViewModel's scope: a `stateIn` sharing coroutine
 * is a child too, and it lives as long as the scope does, so `joinAll` on the lot never
 * returns and "one child left" never holds (both hung F10's first attempt). Snapshot the
 * children first, act, and [current] is exactly what the action started.
 */
class LaunchedJobs(scope: CoroutineScope) {
    private val children = scope.coroutineContext.job.children
    private val before = children.toSet()

    val current: Set<Job> get() = children.toSet() - before
}
