package tv.own.owntv.features.aisubtitles.streaming

/**
 * **Streaming** ASR engines for the AI-subtitle feature.
 *
 * Speech recognition inside OwnTV can run one of two engines:
 *
 * 1. A true-streaming transducer (this catalog): sherpa-onnx streaming Zipformer **Kroko** models,
 *    one per language. They decode incrementally — words appear while the actor is still speaking —
 *    which is what the "live" requirement wants. The **English** model is bundled inside the APK as
 *    assets (`assets/ai/streaming-en/…`, loaded via the asset manager) so it is always ready and
 *    needs no download. French/German/Spanish are downloaded on first use and cached under
 *    `filesDir/ai/streaming/…`; the archive name embeds the release date and each model is a single
 *    transducer (encoder/decoder/joiner + tokens) like any streaming Zipformer.
 *
 * 2. The Whisper **tiny** multilingual model, bundled inside the APK as assets
 *    (`assets/ai/whisper-tiny/…`, loaded via the asset manager; the Silero VAD that slices its
 *    audio is bundled there too). Used for every other language (Italian, Portuguese, Dutch, …).
 *    Whisper is not a streaming model: a Silero VAD slices the audio and each finished utterance
 *    is transcribed in one pass. Slower per utterance and always available offline, it covers
 *    90+ languages — the fallback that keeps every source language usable.
 *
 * The manager picks engine 1 whenever the selected source language has a streaming engine at all
 * (English built in; fr/de/es once downloaded), and engine 2 otherwise. Downloadable archives are
 * fetched from the official sherpa-onnx `asr-models` release and extracted by [StreamingModelStore];
 * the English and Whisper models are always present, so they need no store.
 */
data class StreamingModelInfo(
    /** BCP-47 language code the model recognises, e.g. `"en"`. */
    val language: String,
    /** Extracted directory name (== archive name without the `.tar.bz2` suffix). */
    val archiveName: String,
    /** Direct download URL of the `.tar.bz2` archive. */
    val url: String,
    /** Compressed archive size in bytes; drives the download progress fraction. */
    val sizeBytes: Long,
)

object StreamingModelCatalog {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /**
     * The **built-in** English streaming Kroko engine. The four transducer files ship inside the
     * APK as assets (`assets/ai/streaming-en/…`), handed to sherpa-onnx via the AssetManager — so
     * English needs no download and no disk copy, making the AI-subtitle feature work out-of-the-box
     * for the default source language. Exposed through [builtInEnglish] so the factory and the
     * settings screen can treat it symmetrically with the downloadable models.
     */
    const val BUILT_IN_ENGLISH_LANGUAGE = "en"
    const val BUILT_IN_ENGLISH_ASSETS = "ai/streaming-en"

    /** Streaming models offered for on-demand download, one per language (English is built in). */
    val models: List<StreamingModelInfo> = listOf(
        StreamingModelInfo(
            language = "fr",
            archiveName = "sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06",
            url = "$BASE/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2",
            sizeBytes = 57_220_361,
        ),
        StreamingModelInfo(
            language = "de",
            archiveName = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            url = "$BASE/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06.tar.bz2",
            sizeBytes = 57_565_698,
        ),
        StreamingModelInfo(
            language = "es",
            archiveName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
            url = "$BASE/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
            sizeBytes = 124_394_665,
        ),
    )

    /** The downloadable streaming model for a BCP-47 language, or null when the language falls back to Whisper. */
    fun forLanguage(code: String): StreamingModelInfo? = models.firstOrNull { it.language == code }

    /** True when [code] is English, whose streaming engine is bundled in the APK as assets. */
    fun isBuiltInEnglish(code: String): Boolean = code == BUILT_IN_ENGLISH_LANGUAGE
}
