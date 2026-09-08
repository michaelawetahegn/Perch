package dev.mkiros.perch.model

/**
 * How the reader wants the app to look. Persisted by Settings (T27); [System] follows
 * the platform setting, which is the default because §2 assumes night reading.
 */
enum class ThemeMode { System, Light, Dark }
