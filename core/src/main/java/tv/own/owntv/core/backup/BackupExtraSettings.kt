package tv.own.owntv.core.backup

import org.json.JSONObject

/**
 * Optional hook through which an app layer (e.g. the TV app's AI-subtitle / cloud-translation
 * config) can ride along inside a backup's SETTINGS section.
 *
 * It is a core interface (not a concrete dependency) so the core [BackupManager] stays free of any
 * app-module type. The app module provides one implementation and registers it in Koin; the core
 * DataModule resolves it with `getOrNull()`, so when it is absent (tests, other consumers) the
 * backup behaves exactly as before.
 *
 * The produced JSON is written under a single container key inside the settings block, and the
 * same object is handed back on restore. Implementations decide what they export and how they apply
 * it; a `null` return on export means "nothing to back up".
 */
interface BackupExtraSettings {
    /** Serialize the app-level settings. Return null when there is nothing to carry.
     *  [sealed] is true when the backup is protected by a passphrase (whole container sealed); the
     *  implementation may choose to only export secret fields (API keys, tokens) under [sealed] so
     *  a passwordless backup never spills plaintext credentials — matching BackupManager's policy. */
    suspend fun exportJson(sealed: Boolean = false): JSONObject?

    /** Apply the serialized settings carried by a backup. The object may hold fewer keys than this
     *  build understands (a file written by an older version); only present keys should be applied.
     *  It is null when the backup predates this hook. */
    suspend fun importJson(json: JSONObject?)
}