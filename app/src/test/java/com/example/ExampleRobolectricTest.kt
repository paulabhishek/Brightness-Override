package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sensor.AmbientLightSensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Brightness Override", appName)
  }

  @Test
  fun `verify recommended lux calculations`() {
    val darkLux = AmbientLightSensorManager.calculateRecommendedBrightness(0f)
    assertEquals(5, darkLux)

    val sunLux = AmbientLightSensorManager.calculateRecommendedBrightness(15000f)
    assertEquals(100, sunLux)

    val envDark = AmbientLightSensorManager.getEnvironmentDescription(2f)
    assertTrue(envDark.contains("Dark"))
  }
}

