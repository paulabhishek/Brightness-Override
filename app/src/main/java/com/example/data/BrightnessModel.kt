package com.example.data

data class BrightnessState(
    val windowBrightness: Float = -1f, // -1f = Follow System, 0.01f to 1.0f
    val systemBrightness: Int = 128,   // 0 to 255
    val isAutoBrightness: Boolean = false,
    val canWriteSystemSettings: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val isSubZeroFilterEnabled: Boolean = false,
    val subZeroFilterColor: Long = 0xFF000000, // Black, Amber, Candle, etc.
    val subZeroFilterOpacity: Float = 0.35f,   // 0.05f to 0.85f
    val isKeepScreenAwake: Boolean = false,
    val isFloatingBubbleEnabled: Boolean = false,
    val isPersistentNotificationEnabled: Boolean = false,
    val ambientLux: Float = -1f,
    val screenTimeoutSeconds: Int = 30,
    // App Flare Shield & Auto-Bright Blocker fields
    val isGlobalFlareShieldEnabled: Boolean = true,
    val globalMaxBrightnessCapPercent: Int = 50,
    val blockedFlaresCount: Int = 0,
    val isPerAppAutoDimOnly: Boolean = false,
    val currentForegroundPackage: String? = null,
    val currentForegroundAppName: String? = null
) {
    val effectiveWindowPercent: Int
        get() = if (windowBrightness < 0f) {
            ((systemBrightness / 255f) * 100).toInt().coerceIn(0, 100)
        } else {
            (windowBrightness * 100).toInt().coerceIn(0, 100)
        }
}

data class AppBrightnessRule(
    val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
    val targetBrightnessPercent: Int = 35,
    val blockAutoFlare: Boolean = true,
    val enableSubZeroFilter: Boolean = false,
    val subZeroOpacity: Float = 0.30f
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val rule: AppBrightnessRule? = null
)

data class FlareEvent(
    val id: String,
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    val attemptedBrightnessPercent: Int,
    val clampedBrightnessPercent: Int,
    val details: String
)

data class BrightnessPreset(
    val id: String,
    val name: String,
    val iconType: String,
    val brightnessPercent: Int,
    val isSubZero: Boolean = false,
    val subZeroOpacity: Float = 0f,
    val subZeroColor: Long = 0xFF000000,
    val keepAwake: Boolean = false,
    val isCustom: Boolean = false
)

enum class FilterColor(val label: String, val colorValue: Long, val previewColor: Long) {
    AMOLED_BLACK("OLED Pure Black", 0xFF000000, 0xFF121212),
    WARM_AMBER("Warm Amber (Night)", 0xFFFF8C00, 0xFFFFA726),
    CANDLELIGHT("Cozy Candlelight", 0xFFFF5722, 0xFFFF7043),
    CRIMSON_RED("Aviation Red", 0xFFB71C1C, 0xFFEF5350),
    EMERALD_NIGHT("Forest Green", 0xFF1B5E20, 0xFF66BB6A),
    DEEP_INDIGO("Twilight Indigo", 0xFF1A237E, 0xFF5C6BC0)
}
