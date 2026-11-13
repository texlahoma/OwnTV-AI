package tv.own.owntv.core.player

/**
 * The stable identity a per-item engine pin ([ForceMpvStore] live "compatibility mode",
 * [VodEngineStore] VOD gear toggle) is keyed on.
 *
 * Both stores originally keyed on the item's stream URL, which is stable for M3U and Xtream but
 * **never** for Stalker/MAC portals: `StreamUrlResolver.resolve()` asks the portal for a URL at play
 * time and gets back a fresh, token-bearing, single-use one — so the URL stored when the pin was made
 * could never match the URL of the next play, and the pin silently did nothing on every Stalker item.
 *
 * `sourceId` + media type + provider `remoteId` is carried by every content entity of every source
 * type and survives re-syncs (Room ids do not). Returns null when the row has no `remoteId` (some
 * hand-made M3U rows), in which case callers keep using the stream URL — which for those rows is
 * exactly as stable as it always was.
 *
 * Both key shapes coexist in the same stored set: reads try the stable key, then the legacy URL, and
 * a legacy hit is rewritten under the stable key (migrate-on-read). That also keeps backups
 * compatible in both directions — the stores export one set either way, and a key an older build
 * doesn't recognise is simply never matched.
 */
fun enginePinKey(sourceId: Long, mediaType: String, remoteId: String?): String? =
    remoteId?.takeIf { it.isNotBlank() }?.let { "$sourceId:$mediaType:$it" }

/**
 * The one key for everything played out of [channel]'s archive, catch-up programmes and live
 * rewinds alike. Their URLs carry the time (and the credentials), so a pin or a remembered zoom/volume
 * keyed on the URL never matched again and piled up. Its own media type, `CATCHUP`, rather than the
 * channel's `LIVE` key: live and film-style pins share one table and are told apart by that type, so
 * an archive pin under the live key would re-pin the live channel and never be read back for the archive.
 */
fun catchupPinKey(channel: tv.own.owntv.core.database.entity.ChannelEntity): String? =
    enginePinKey(channel.sourceId, CATCHUP_PIN_TYPE, channel.remoteId)

private const val CATCHUP_PIN_TYPE = "CATCHUP"

/** The playlist an [enginePinKey] belongs to, or `-1` for a legacy stream-URL key. */
fun sourceIdOfPinKey(key: String): Long = key.substringBefore(':').toLongOrNull() ?: -1L

/** The media type an [enginePinKey] names (`LIVE`, `MOVIE`, `EPISODE`, `CATCHUP`), or null for a stream-URL key. */
fun mediaTypeOfPinKey(key: String): String? =
    if (sourceIdOfPinKey(key) < 0) null else key.substringAfter(':').substringBefore(':').takeIf { it.isNotEmpty() }
