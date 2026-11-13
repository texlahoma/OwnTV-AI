package tv.own.owntv.core.theme

import androidx.compose.runtime.Stable

/**
 * The user's Glass setting: on or off, and how opaque the glass is. The drawing lives with each app's theme.
 *
 * @param alpha the glass fill's opacity when on, in 0..1.
 */
@Stable
data class GlassConfig(
    val enabled: Boolean = false,
    val alpha: Float = DEFAULT_GLASS_ALPHA,
) {
    companion object {
        const val DEFAULT_GLASS_ALPHA: Float = 0.5f

        /**
         * What "on" stores. The setting is persisted as a bitmask (`glass_scope`, carried by backups) from the
         * time glass could be switched per surface; any bit set means on. These are the seven surfaces older
         * versions turned on, so a backup made here still reads as on in them — never change the value.
         */
        const val ON_BITS: Int = 0b111_1111
    }
}
