package com.example.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class BrightnessTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        // Toggle sequence: Normal (50%) -> Max Boost (100%) -> Sub-Zero (10%) -> Auto
        if (Settings.System.canWrite(this)) {
            val currentBrightness = try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                128
            }

            val nextBrightness = when {
                currentBrightness < 50 -> 255 // Go to Max 100%
                currentBrightness > 200 -> 25 // Go to Sub-Zero Low 10%
                else -> 255                   // Boost to Max
            }

            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    nextBrightness
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val currentBrightness = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        val percent = (currentBrightness * 100 / 255)

        tile.label = "Brightness: $percent%"
        tile.state = if (percent > 80) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (percent > 80) "Sunlight Boost" else if (percent < 20) "Night Dim" else "Standard"
        }
        tile.updateTile()
    }
}
