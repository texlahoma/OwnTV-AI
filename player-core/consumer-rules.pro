# Keep rules that :player-core imposes on anything consuming it (merged into a consumer's R8 run
# via consumerProguardFiles). Each rule guards a runtime lookup R8 can't see statically — trim
# only with a full on-device regression (sync, both engines, backup).

# --- libmpv JNI: native code resolves MPVLib and its event callbacks by name ---
-keep class dev.jdtech.mpv.** { *; }
