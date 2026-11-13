package tv.own.owntv.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.FocusableSurface
import tv.own.owntv.ui.components.OwnTVAvatar
import tv.own.owntv.ui.components.ProfileIcon
import tv.own.owntv.ui.components.OwnTVAvatars
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.modalScrim
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.ui.theme.OwnTVTheme

/** Full-screen avatar picker: a grid of the preset cartoon avatars. Picking one applies & closes. */
@Composable
fun AvatarPickerDialog(
    selectedId: Int,
    onSelect: (Int) -> Unit,
    // A picture of the user's own, when this profile has one — it takes the place of the drawn tile
    // wherever the avatar appears. Blank means none, and the two callbacks below are absent when the
    // host has nowhere to pick a picture from.
    customPath: String = "",
    onPickCustom: (() -> Unit)? = null,
    onClearCustom: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val colors = OwnTVTheme.colors
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { selectedFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .modalScrim()
            // Opened by a long-press of OK — without this guard the still-held release would instantly
            // confirm the focused avatar (the "auto-selects first, no pause" bug). longPressMenuGuard
            // swallows OK/Enter until the key is released once, so the user navigates + OK to pick.
            .longPressMenuGuard()
            .trapAllFocusExit()
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surfaceContainerHigh)
                // Scrollable: the avatar grid is taller than small/low-res screens.
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.content_avatar_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            // Phase 7 — "no avatar" option showing the Rank 1 ProfileIcon (ID -1)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val noneSelected = selectedId == -1
                FocusableSurface(
                    onClick = { onSelect(-1); onDismiss() },
                    modifier = (if (noneSelected) Modifier.focusRequester(selectedFocus) else Modifier)
                        .size(88.dp),
                    selected = noneSelected,
                    shape = RoundedCornerShape(22.dp),
                    focusedScale = 1.03f,
                    focusedContainerColor = colors.surfaceContainerHighest,
                    unfocusedContainerColor = colors.surfaceContainer,
                    selectedContainerColor = colors.primaryContainer,
                    contentAlignment = Alignment.Center,
                    surface = GlassSurface.DIALOGS,
                ) { _ ->
                    ProfileIcon(color = OwnTVTheme.colors.primary, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            // A picture of your own, alongside the drawn set. Same two ways in as the background
            // image — a file on this device, or a photo sent from a phone — so there is one idea to
            // learn rather than two; the host supplies that chooser.
            if (onPickCustom != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusableSurface(
                        onClick = onPickCustom,
                        modifier = Modifier.size(88.dp),
                        selected = customPath.isNotBlank(),
                        shape = RoundedCornerShape(22.dp),
                        focusedScale = 1.03f,
                        focusedContainerColor = colors.surfaceContainerHighest,
                        unfocusedContainerColor = colors.surfaceContainer,
                        selectedContainerColor = colors.primaryContainer,
                        contentAlignment = Alignment.Center,
                        surface = GlassSurface.DIALOGS,
                    ) { _ ->
                        if (customPath.isNotBlank()) {
                            OwnTVAvatar(avatarId = selectedId, imagePath = customPath, modifier = Modifier.size(64.dp))
                        } else {
                            ProfileIcon(color = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        }
                    }
                    Text(
                        text = stringResource(R.string.profiles_avatar_own_picture),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    if (customPath.isNotBlank() && onClearCustom != null) {
                        tv.own.owntv.ui.components.OwnTVButton(
                            label = stringResource(R.string.common_clear),
                            onClick = onClearCustom,
                            style = tv.own.owntv.ui.components.OwnTVButtonStyle.SECONDARY,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            val ids = (0 until OwnTVAvatars.COUNT).toList()
            ids.chunked(4).forEach { rowIds ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    rowIds.forEach { id ->
                        val isSelected = id == selectedId
                        FocusableSurface(
                            onClick = { onSelect(id); onDismiss() },
                            modifier = (if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier)
                                .size(88.dp),
                            selected = isSelected,
                            shape = RoundedCornerShape(22.dp),
                            focusedScale = 1.03f,
                            focusedContainerColor = colors.surfaceContainerHighest,
                            unfocusedContainerColor = colors.surfaceContainer,
                            selectedContainerColor = colors.primaryContainer,
                            contentAlignment = Alignment.Center,
                            surface = GlassSurface.DIALOGS,
                        ) { _ ->
                            OwnTVAvatar(avatarId = id, modifier = Modifier.size(64.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
