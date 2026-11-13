package tv.own.owntv.player

import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The one place every in-app ExoPlayer's renderers are configured.
 *
 * Three engines build an ExoPlayer — [LivePreviewEngine] (Live TV's default engine),
 * [ExoSubtitleEngine] (VOD / image-subtitle handoff / mpv fallback) and [HeroPreviewEngine] (the
 * home hero). Each used to configure its own, so a decode-path setting could land on one and not the
 * others: the async-queueing workaround below was live-only, and decoder fallback was on none of
 * them. Anything about *how the device decodes* belongs here so it cannot go missing on one engine
 * again. Per-engine concerns (data source, load control, track selector, listeners) stay at the
 * call site, where they legitimately differ.
 */
@UnstableApi
fun ownTVRenderers(
    context: Context,
    /** Pin the audio sink to stereo PCM — "Stereo only", or a session latch tripped by any engine. */
    forceStereo: Boolean,
    /** Software decoders first — "Hardware decoding = Off", or a rescue retry after a blank picture. */
    softwareFirst: Boolean = false,
    /**
     * This engine's live audio hold-back, ms (0 = none), or null when this engine can never need one
     * — VOD, the hero preview and every muted tile keep the stock audio path, untouched. Only the
     * live engine passes a provider, and it reads it per buffer, so captions switched on mid-play
     * start delaying immediately.
     */
    audioDelayMs: (() -> Int)? = null,
): DefaultRenderersFactory =
    OwnTVRenderersFactory(context, forceStereo = forceStereo, audioDelayMs = audioDelayMs)
        // Media3 runs MediaCodec asynchronously by default on API 31+, and on every Fire TV device
        // (`com.amazon.hardware.tv_screen`) from API 28 up. That async path corrupts (macroblocks)
        // some UHD-HEVC content on Realtek/Amlogic VPUs — the synchronous path is what players like
        // TiviMate use to avoid it. The corruption is a property of the VPU, not of the transport, so
        // a 4K HEVC *file* needs this exactly as much as a 4K HEVC channel does. Content it still
        // can't decode cleanly is handed to mpv (per-channel "force mpv" routing / the VOD fallback).
        // The disable is therefore scoped to the affected VPU families — see vpuCorruptsUnderAsyncQueueing
        // — rather than applied to every device: other SoCs (the MediaTek ones in current TVs) take
        // Media3's stock async path with no corruption and a slightly cheaper codec pipeline.
        .apply {
            if (vpuCorruptsUnderAsyncQueueing()) forceDisableMediaCodecAsynchronousQueueing()
        }
        // If the chosen decoder fails to initialise or throws while configuring, try the next one the
        // device offers (usually the software decoder) instead of surfacing the error. A free rung on
        // the rescue ladder: without it one bad vendor decoder ends playback outright.
        .setEnableDecoderFallback(true)
        .apply {
            if (softwareFirst) {
                // Hardware stays in the list as a backstop — Media3 walks the decoder list in order
                // and falls through on failure, so this can only add a route.
                setMediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                        .sortedBy { it.hardwareAccelerated } // false (software) sorts before true
                }
            }
        }

/**
 * SoC families whose VPUs corrupt UHD-HEVC under Media3's async MediaCodec queueing, detected from
 * the usual [Build] strings: the vendor (Realtek/Amlogic) or the board/chip model (rk* for Rockchip,
 * g12a/b, sm1, the s905 family and t96x for Amlogic). A false positive merely keeps the synchronous
 * path — which is always safe — so the list errs on the inclusive side rather than risk a missed
 * Realtek/Amlogic board corrupting 4K.
 */
private val ASYNC_CORRUPTING_VPU_FAMILIES = listOf(
    "realtek", "amlogic", "rk2", "rk3", "g12a", "g12b", "sm1", "s905", "s912", "s922x",
    "t962", "t968", "t972", "t982",
)

/** Whether this device's VPU needs the synchronous MediaCodec path (see [ASYNC_CORRUPTING_VPU_FAMILIES]). */
private fun vpuCorruptsUnderAsyncQueueing(): Boolean {
    val haystack = buildString {
        append(Build.MANUFACTURER).append(' ')
        append(Build.HARDWARE).append(' ')
        append(Build.BOARD).append(' ')
        append(Build.DEVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) append(' ').append(Build.SOC_MODEL)
    }.lowercase()
    return ASYNC_CORRUPTING_VPU_FAMILIES.any { haystack.contains(it) }
}
