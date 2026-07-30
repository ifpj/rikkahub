package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.modifier.shimmer

@Composable
fun ChainOfThoughtScope.ChatMessageWebSearchStep(search: UIMessagePart.WebSearch) {
    val detail = search.query ?: search.url ?: search.pattern
    val label = when {
        !search.isCompleted && detail != null -> stringResource(R.string.chat_message_builtin_search_searching_for, detail)
        !search.isCompleted -> stringResource(R.string.chat_message_builtin_search_searching)
        detail != null -> stringResource(R.string.chat_message_builtin_search_completed_for, detail)
        else -> stringResource(R.string.chat_message_builtin_search_completed)
    }

    ChainOfThoughtStep(
        icon = {
            if (search.isCompleted) {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            } else {
                DotLoading(size = 10.dp)
            }
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = !search.isCompleted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (search.sources.isNotEmpty()) {
            {
                Text(
                    text = stringResource(R.string.chat_message_builtin_search_sources, search.sources.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
}
