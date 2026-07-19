package ai.darshj.djproxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.darshj.djproxy.ui.components.ConnectButton
import ai.darshj.djproxy.ui.components.GlassSurface
import ai.darshj.djproxy.ui.components.StageLabel
import ai.darshj.djproxy.ui.theme.DjBackgroundBrush
import ai.darshj.djproxy.ui.theme.DjColors
import ai.darshj.djproxy.vpn.VpnStage

/**
 * The single screen. Root layout is one scrollable [LazyColumn] so the paste box, fields, CTA,
 * error card, status card, and log all live in one continuous flow with no nested-scroll fights.
 */
@Composable
fun ProxyScreen(viewModel: ProxyViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val controllerReady by viewModel.controllerReady.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DjBackgroundBrush),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column {
                    Text(
                        "DJProxy",
                        style = MaterialTheme.typography.displaySmall,
                        color = DjColors.TextPrimary,
                    )
                    Text(
                        "Device-wide, fail-closed proxy tunnel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DjColors.TextSecondary,
                    )
                }
            }

            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    PasteBox(
                        text = ui.pasteText,
                        onTextChange = viewModel::onPasteTextChanged,
                        parseError = ui.pasteError,
                    )
                }
            }

            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    ProxyFields(
                        config = ui.config,
                        onConfigChange = viewModel::onConfigChanged,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ConnectButton(
                        stage = vpnState.stage,
                        onClick = {
                            if (vpnState.isUp || vpnState.stage == VpnStage.RECONNECTING) {
                                viewModel.onStop()
                            } else if (!vpnState.isBusy) {
                                viewModel.onApply()
                            }
                        },
                    )
                    StageLabel(stage = vpnState.stage)
                    if (!controllerReady) {
                        Text(
                            "Preparing VPN service…",
                            style = MaterialTheme.typography.bodySmall,
                            color = DjColors.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = ui.validationError != null,
                    enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
                ) {
                    ui.validationError?.let { error ->
                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            borderBrush = androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(DjColors.Rose, DjColors.RoseDeep),
                            ),
                            fill = androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(DjColors.Rose.copy(alpha = 0.14f), DjColors.Rose.copy(alpha = 0.05f)),
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(error.message, style = MaterialTheme.typography.titleSmall, color = DjColors.TextPrimary)
                                Text(
                                    error.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DjColors.TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = viewModel::dismissValidationError) {
                                        Text("Dismiss", color = DjColors.Rose)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                StatusCard(state = vpnState, modifier = Modifier.fillMaxWidth())
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Live log",
                        style = MaterialTheme.typography.titleMedium,
                        color = DjColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 320.dp),
                        contentPadding = 0.dp,
                    ) {
                        LogView(events = logs, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
