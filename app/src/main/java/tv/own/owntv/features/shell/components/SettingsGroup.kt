package tv.own.owntv.features.shell.components

import tv.own.owntv.R
import tv.own.owntv.features.settings.VideoGroup

/**
 * The twelve Settings groups, in the order of More › Settings' cards — a card's index is the
 * group it opens. [video] marks the five groups whose rows are the Video player's.
 */
internal enum class SettingsGroup(val titleRes: Int, val video: VideoGroup? = null) {
    QUICK(R.string.settings_group_quick),
    PROFILE(R.string.settings_group_profile),
    SOURCES(R.string.settings_group_sources),
    APPEARANCE(R.string.settings_group_appearance),
    LAYOUT(R.string.settings_group_layout),
    CONTENT(R.string.settings_group_content_metadata),
    PLAYER(R.string.settings_vp_cat_player, VideoGroup.PLAYER),
    PICTURE(R.string.settings_vp_cat_picture, VideoGroup.PICTURE),
    SOUND(R.string.settings_group_sound_subtitles, VideoGroup.SOUND),
    LIVE(R.string.settings_live_tv, VideoGroup.LIVE),
    WATCHING(R.string.settings_group_watching_recording, VideoGroup.WATCHING),
    APP(R.string.settings_group_app),
    ;

    companion object {
        /** The group a Video player row lives in; Diagnostics sits at the end of App. */
        fun of(group: VideoGroup): SettingsGroup = entries.firstOrNull { it.video == group } ?: APP
    }
}
