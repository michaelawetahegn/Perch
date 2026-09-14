package dev.mkiros.perch.ui

/**
 * How long a `WhileSubscribed` state flow keeps its upstream alive after the last collector
 * leaves. Five seconds outlives a rotation, so a screen's query is not torn down and rebuilt
 * for a configuration change. One value for every ViewModel (#63).
 */
internal const val STOP_TIMEOUT_MS = 5_000L
