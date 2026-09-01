package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.log10

class AmbientLightSensorManager(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val hasLightSensor: Boolean = lightSensor != null

    fun getLightFlow(): Flow<Float> = callbackFlow {
        if (lightSensor == null) {
            trySend(-1f)
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.isNotEmpty()) {
                    val lux = event.values[0]
                    trySend(lux)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)

        awaitClose {
            sensorManager?.unregisterListener(listener)
        }
    }

    companion object {
        fun getEnvironmentDescription(lux: Float): String = when {
            lux < 0 -> "Sensor Unavailable"
            lux < 5 -> "Pitch Dark (Bedtime)"
            lux < 25 -> "Moonlit / Night Lamp"
            lux < 100 -> "Dim Indoor Room"
            lux < 500 -> "Normal Living Room"
            lux < 1000 -> "Bright Office / Studio"
            lux < 5000 -> "Daylight / Overcast Sky"
            lux < 25000 -> "Bright Outdoor Shade"
            else -> "Direct Sunlight"
        }

        /**
         * Logarithmic ambient-to-display brightness calculation
         * Maps 0..50000 lux smoothly to 5%..100% brightness
         */
        fun calculateRecommendedBrightness(lux: Float): Int {
            if (lux <= 0f) return 5
            if (lux < 2f) return 5
            if (lux > 10000f) return 100

            // Log scale mapping
            val logLux = log10(lux.coerceIn(1f, 10000f).toDouble()) // 0 to 4
            val normalized = (logLux / 4.0).toFloat() // 0.0 to 1.0
            val percent = (5 + (normalized * 95)).toInt()
            return percent.coerceIn(5, 100)
        }
    }
}
