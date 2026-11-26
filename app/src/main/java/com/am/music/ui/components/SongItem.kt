package com.am.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.data.model.Song
import com.am.music.ui.theme.pressScale
import com.am.music.util.formatDuration
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.MotionIconButton

@Composable
fun SongItem(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMoreClick: (() -> Unit)? = null,
    /** 右侧的小角标（已下载的档位、实测码率等）；null 就不占位置。 */
    badge: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(0.985f)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            uri = song.albumArtUri,
            size = 44.dp,
            shape = RoundedCornerShape(2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, formatDuration(song.durationMs))
                    .filter { it.isNotBlank() && it != "0:00" }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        badge?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // 播放中的小三角：开始播这首时弹出来，切走时缩回去，而不是凭空多一个字
        IconAppear(visible = isCurrent) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (onMoreClick != null) {
            MotionIconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "更多操作",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}



