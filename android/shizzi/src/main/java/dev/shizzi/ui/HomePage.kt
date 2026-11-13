package dev.shizzi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shizzi.HotspotClient
import dev.shizzi.HotspotClientsState
import dev.shizzi.SessionUiState
import dev.shizzi.UiStatus
import dev.shizzi.Traffic
import dev.shizzi.ui.theme.HeaderHeight
import dev.shizzi.ui.theme.ScreenPadding
import dev.shizzi.ui.theme.ShizziTheme
import dev.shizzi.ui.theme.standardSpring
import dev.shizzi.ui.theme.standardTween

private fun buttonLabel(status: UiStatus): String =
    if (status == UiStatus.CONNECTED) "Stop" else "Start"

private fun buttonState(state: SessionUiState): ConnectButtonState = when {
    state.status == UiStatus.LOADING -> ConnectButtonState.LOADING
    state.status == UiStatus.CONNECTED -> ConnectButtonState.STOP
    state.canStart -> ConnectButtonState.START
    else -> ConnectButtonState.DISABLED
}

@Composable
fun HomePage(state: SessionUiState, actions: HomeActions) {
    var showClients by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
    ) {
        HomeHeader(
            state = state,
            onOpenSettings = actions.onOpenSettings,
            onOpenDevices = {
                showClients = true
                actions.onRefreshClients()
            },
        )

        HomeBody(state = state, actions = actions)

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.height(ShizziTheme.spacing.xxxl),
                contentAlignment = Alignment.BottomCenter,
            ) {
                RiseIn(isVisible = isShowingVpn(state)) {
                    VpnChip(isBypassed = state.isVpnBypassed)
                }
            }
            StatusRow(state = state, onVersionClick = actions.onOpenEasterEgg)
        }
    }

    if (showClients) {
        HotspotClientsDialog(
            state = state.hotspotClients,
            connectedCount = state.clientCount,
            isSessionActive = state.status == UiStatus.CONNECTED,
            onDismiss = { showClients = false },
            onRefresh = actions.onRefreshClients,
            onSetBlocked = actions.onSetClientBlocked,
        )
    }
}

data class HomeActions(
    val onToggle: () -> Unit,
    val onCancel: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenEasterEgg: () -> Unit,
    val onRefreshClients: () -> Unit,
    val onSetClientBlocked: (String, Boolean) -> Unit,
)

@Composable
private fun HomeHeader(
    state: SessionUiState,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = ShizziTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.padding(start = ShizziTheme.spacing.sm)) {
            ShizukuBadge(state.shizukuState)
        }
        Spacer(Modifier.weight(1f))
        ShizziIconButton(
            icon = Icons.Filled.Devices,
            contentDescription = "Connected hotspot devices",
            onClick = onOpenDevices,
            tint = ShizziTheme.colors.onSurfaceMuted,
        )
        ShizziIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "Settings",
            onClick = onOpenSettings,
            tint = ShizziTheme.colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun HotspotClientsDialog(
    state: HotspotClientsState,
    connectedCount: Int,
    isSessionActive: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSetBlocked: (String, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connected devices", modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = isSessionActive) { Text("Refresh") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isSessionActive) "$connectedCount connected · ${state.clients.count { it.blocked }} blocked"
                    else "Start the hotspot to inspect connected devices",
                    style = ShizziTheme.typography.caption,
                    color = ShizziTheme.colors.onSurfaceMuted,
                )
                if (state.clients.isEmpty()) {
                    Text(
                        if (isSessionActive && connectedCount > 0) "Android reports $connectedCount connected device(s), but this firmware did not expose their MAC addresses." else if (isSessionActive) state.message else "No live device inventory while the hotspot is stopped.",
                        color = ShizziTheme.colors.onSurfaceMuted,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.clients, key = { it.macAddress }) { client ->
                            HotspotClientCard(client, state.blockSupported, onSetBlocked)
                        }
                    }
                }
                Text(
                    if (state.usageAvailable) "Cumulative per-device payload usage is saved locally and grouped by MAC address."
                    else state.message,
                    style = ShizziTheme.typography.caption,
                    color = ShizziTheme.colors.onSurfaceMuted,
                )
                state.actionMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = ShizziTheme.typography.caption,
                        color = ShizziTheme.colors.primary,
                    )
                }
                if (!state.blockSupported && isSessionActive) {
                    Text(
                        "This device does not expose the required Soft AP block-list API or permission.",
                        style = ShizziTheme.typography.caption,
                        color = ShizziTheme.colors.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = ShizziTheme.colors.surface,
    )
}

@Composable
private fun HotspotClientCard(
    client: HotspotClient,
    blockSupported: Boolean,
    onSetBlocked: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ShizziTheme.colors.background),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (client.blocked) "Blocked device" else if (client.connected) "Connected device" else "Saved blocked device",
                color = ShizziTheme.colors.primary,
                style = ShizziTheme.typography.caption,
            )
            Text(client.macAddress, color = ShizziTheme.colors.onSurface)
            Text(
                client.ipAddress?.let { "IP address · $it" } ?: "IP address · unavailable",
                color = ShizziTheme.colors.onSurfaceMuted,
                style = ShizziTheme.typography.caption,
            )
            Text(
                if (client.usageAvailable) "Download · " + Traffic.format(client.rxBytes) + "   Upload · " + Traffic.format(client.txBytes)
                else "Data usage · unavailable for this device",
                color = ShizziTheme.colors.onSurfaceMuted,
                style = ShizziTheme.typography.caption,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = blockSupported,
                    onClick = { onSetBlocked(client.macAddress, !client.blocked) },
                ) {
                    Text(if (client.blocked) "Unblock" else "Block device")
                }
            }
        }
    }
}

private fun isShowingVpn(state: SessionUiState): Boolean {
    val hasVpn = state.isVpnBound || state.isVpnBypassed
    return hasVpn && state.status == UiStatus.CONNECTED
}

@Composable
private fun RiseIn(isVisible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(standardTween()) + slideInVertically(standardSpring()) { it / 2 },
        exit = fadeOut(standardTween()) + slideOutVertically(standardSpring()) { it / 2 },
    ) {
        content()
    }
}

@Composable
private fun HomeBody(state: SessionUiState, actions: HomeActions) {
    val isStarting = state.status == UiStatus.LOADING
    Column(
        modifier = Modifier.fillMaxSize().padding(ScreenPadding).padding(top = ShizziTheme.spacing.xxxl * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusIcon(status = state.status)
        Spacer(Modifier.height(ShizziTheme.spacing.xxxl * 2))
        ConnectButton(label = buttonLabel(state.status), state = buttonState(state), onClick = actions.onToggle)
        Box(modifier = Modifier.height(ShizziTheme.spacing.xxxl)) {
            RiseIn(isVisible = isStarting) { CancelButton(onClick = actions.onCancel) }
        }
    }
}
