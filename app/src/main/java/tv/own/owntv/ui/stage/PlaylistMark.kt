package tv.own.owntv.ui.stage

import androidx.compose.ui.graphics.Color

/** The playlist mark ("GOLD") lives in core, shared with the phone; its colour is an ARGB long there. */
typealias PlaylistMark = tv.own.owntv.core.ui.stage.PlaylistMark

val PlaylistMark.color: Color get() = Color(argb)
