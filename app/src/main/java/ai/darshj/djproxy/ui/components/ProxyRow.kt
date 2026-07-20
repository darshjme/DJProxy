package ai.darshj.djproxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.store.ProxyStatus
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.ui.theme.MotionTokens

/** One overflow-menu action on a [ProxyRow] (Edit / Delete / Set default / Move up-down / Save …). */
data class RowAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** A small labelled pill shown before the title — e.g. "Default" (cyan) or "Public" (amber). */
data class RowBadge(val label: String, val accent: androidx.compose.ui.graphics.Color)

/**
 * v6 (§5.3): one saved/free proxy row in the Servers list. A glass container carrying the name +
 * badges, the redacted `type://host:port` line, a live [StatusChip], and a trailing overflow menu.
 * Tapping the body is the PRIMARY action (reuse/apply for saved, use-now for free); tapping the chip
 * re-checks just this row. Everything is keyboard/TalkBack reachable — the body is a Button role and
 * every action is a menu item.
 */
@Composable
fun ProxyRow(
    title: String,
    subtitle: String,
    status: ProxyStatus,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onRecheck: () -> Unit,
    actions: List<RowAction>,
    modifier: Modifier = Modifier,
    badges: List<RowBadge> = emptyList(),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.97f else 1f, MotionTokens.SpatialSpring, label = "row-press")
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(press)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DjColors.GlassFillStrong, DjColors.GlassFill)))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(DjColors.AccentCyan.copy(alpha = 0.30f), DjColors.AccentIndigo.copy(alpha = 0.18f)),
                ),
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = primaryLabel,
                role = Role.Button,
                onClick = onPrimary,
            )
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { badge ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(badge.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(badge.label, style = MaterialTheme.typography.labelSmall, color = badge.accent)
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = DjColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = DjColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            // The chip is tappable to re-check only this row (§3.4).
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClickLabel = "Re-check", role = Role.Button, onClick = onRecheck)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Re-check this proxy"
                    },
            ) {
                StatusChip(status = status)
            }
        }

        OverflowMenu(actions = actions)
    }
}

@Composable
private fun OverflowMenu(actions: List<RowAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions", tint = DjColors.TextSecondary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) DjColors.Rose else DjColors.TextPrimary,
                        )
                    },
                    leadingIcon = action.icon?.let {
                        {
                            Icon(
                                it,
                                contentDescription = null,
                                tint = if (action.destructive) DjColors.Rose else DjColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}
