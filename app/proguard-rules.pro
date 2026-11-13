# OwnTV release keep rules (R8). Each rule guards a runtime lookup that R8 can't see
# statically — trim only with a full on-device regression (sync, both engines, backup).

# --- Crash reports: keep readable stack traces (mapping.txt still needed for names) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- libmpv JNI: native code resolves MPVLib and its event callbacks by name ---
# Mirrored in OwnTV_Core/player-core/consumer-rules.pro (libmpv is a :player-core api dependency).
# Both copies may stay: identical keep rules are idempotent under R8. Drop this one after core
# publishes a version whose player-core consumer-rules carry it (then GPR resolves that version).
-keep class dev.jdtech.mpv.** { *; }

# --- sherpa-onnx JNI (AI-subtitle Whisper ASR): native code resolves StreamingRecognizer by name ---
-keep class com.k2fsa.sherpa.onnx.** { *; }

# --- WorkManager: workers are re-instantiated by FQCN string (KoinWorkerFactory matches
#     workerClassName against ::class.java.name, and WorkManager persists the name in its DB
#     across app updates) ---
# Mirrored in OwnTV_Core/core/consumer-rules.pro; identical rules are idempotent under R8, keep
# both until core publishes this rule (see the libmpv note above).
-keep class * extends androidx.work.ListenableWorker

# --- Room: Room.getGeneratedImplementation() derives this exact class name and loads it
#     reflectively. Keep the generated database entry point in optimized release builds. ---
# Mirrored in OwnTV_Core/core/consumer-rules.pro; identical rules are idempotent under R8, keep
# both until core publishes this rule (see the libmpv note above).
-keep class tv.own.owntv.core.database.OwnTVDatabase_Impl { *; }

# --- Persisted enum names: DataStore prefs, backup JSON ("t" = MediaType.name), and
#     per-setting modes (ZoomMode, ThemeMode, StartupMode, AnimationLevel, ResumeMode,
#     EpgAutoRefresh, PlaylistAutoRefresh, ...) round-trip through Enum.name/valueOf.
#     Renaming a constant would silently reset settings and break old backups. ---
# Mirrored in OwnTV_Core/core/consumer-rules.pro; identical rules are idempotent under R8, keep
# both until core publishes this rule (see the libmpv note above).
-keep enum tv.own.owntv.** { *; }
