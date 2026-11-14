package dev.shizzi

import android.service.quicksettings.Tile

enum class TileAction { START, STOP, OPEN_APP, NONE }

data class TileRender(val state: Int, val subtitle: String, val action: TileAction)

object SessionTile {
    const val LABEL = "Shizzi"

    fun render(session: SessionUiState, shizuku: ShizukuState, isStopping: Boolean): TileRender {
        if (shizuku !is ShizukuState.Ready) return unavailable(shizuku)

        return when (session.status) {
            UiStatus.LOADING -> TileRender(Tile.STATE_UNAVAILABLE, if (isStopping) "Stopping…" else "Starting…", TileAction.NONE)
            UiStatus.CONNECTED -> TileRender(Tile.STATE_ACTIVE, describeConnected(session), TileAction.STOP)
            UiStatus.ERROR -> TileRender(Tile.STATE_INACTIVE, "Tap to retry", TileAction.START)
            UiStatus.READY -> TileRender(Tile.STATE_INACTIVE, "Tap to share", TileAction.START)
        }
    }

    private fun unavailable(shizuku: ShizukuState) = TileRender(Tile.STATE_UNAVAILABLE, describe(shizuku), TileAction.OPEN_APP)

    private fun describe(shizuku: ShizukuState): String = when (shizuku) {
        ShizukuState.NotInstalled -> "Shizuku not installed"
        ShizukuState.NotRunning -> "Shizuku not running"
        ShizukuState.PermissionRequired -> "Permission required"
        is ShizukuState.UnsupportedIdentity -> "Unsupported Shizuku UID ${shizuku.uid}"
        is ShizukuState.Ready -> ""
    }

    private fun describeConnected(session: SessionUiState): String = when (session.clientCount) {
        0 -> "No devices"
        1 -> "1 device · ${Traffic.format(session.traffic.down)}"
        else -> "${session.clientCount} devices · ${Traffic.format(session.traffic.down)}"
    }
}