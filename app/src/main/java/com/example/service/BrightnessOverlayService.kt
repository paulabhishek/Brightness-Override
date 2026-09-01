package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppBrightnessRule
import com.example.sensor.AppUsageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class BrightnessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var filterOverlayView: View? = null

    private var currentOpacity: Float = 0.40f
    private var currentColor: Long = 0xFF000000
    private var isFilterActive: Boolean = false
    private var activeOverriddenApp: String? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("brightness_override_prefs", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE_STATE

        when (action) {
            ACTION_START_FILTER -> {
                currentOpacity = intent?.getFloatExtra(EXTRA_OPACITY, currentOpacity) ?: currentOpacity
                currentColor = intent?.getLongExtra(EXTRA_COLOR, currentColor) ?: currentColor
                isFilterActive = true
                applyFilterOverlay()
            }
            ACTION_STOP_FILTER -> {
                isFilterActive = false
                removeFilterOverlay()
            }
            ACTION_UPDATE_STATE -> {
                if (intent != null) {
                    if (intent.hasExtra(EXTRA_OPACITY)) {
                        currentOpacity = intent.getFloatExtra(EXTRA_OPACITY, currentOpacity)
                    }
                    if (intent.hasExtra(EXTRA_COLOR)) {
                        currentColor = intent.getLongExtra(EXTRA_COLOR, currentColor)
                    }
                    if (intent.hasExtra(EXTRA_FILTER_ACTIVE)) {
                        isFilterActive = intent.getBooleanExtra(EXTRA_FILTER_ACTIVE, isFilterActive)
                    }
                }
                if (isFilterActive) applyFilterOverlay() else removeFilterOverlay()
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun startWatchdogLoop() {
        serviceScope.launch {
            var previousForegroundPkg: String? = null
            var wasOverridden = false

            while (isActive) {
                delay(700)
                if (!AppUsageHelper.hasUsageStatsPermission(this@BrightnessOverlayService)) {
                    continue
                }

                val foreground = AppUsageHelper.getForegroundApp(this@BrightnessOverlayService) ?: continue
                val (pkg, name) = foreground

                if (pkg != previousForegroundPkg) {
                    previousForegroundPkg = pkg
                    val rules = loadRulesFromPrefs()
                    val rule = rules[pkg]

                    if (rule != null && rule.isEnabled) {
                        // Apply override for the selected app
                        activeOverriddenApp = name
                        wasOverridden = true
                        currentOpacity = rule.subZeroOpacity
                        isFilterActive = true

                        // Switch to main thread to update UI overlay
                        launch(Dispatchers.Main) {
                            applyFilterOverlay()
                            // Also adjust system brightness if permission granted
                            if (Settings.System.canWrite(this@BrightnessOverlayService)) {
                                setSystemBrightness(rule.targetBrightnessPercent * 255 / 100)
                            }
                            updateNotification()
                        }
                    } else if (wasOverridden) {
                        // Exited the configured app -> immediately remove override
                        wasOverridden = false
                        activeOverriddenApp = null
                        isFilterActive = false

                        launch(Dispatchers.Main) {
                            removeFilterOverlay()
                            updateNotification()
                        }
                    }
                }
            }
        }
    }

    private fun loadRulesFromPrefs(): Map<String, AppBrightnessRule> {
        val map = mutableMapOf<String, AppBrightnessRule>()
        val jsonStr = prefs.getString("app_brightness_rules", null) ?: return map
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rule = AppBrightnessRule(
                    packageName = obj.getString("packageName"),
                    appName = obj.getString("appName"),
                    isEnabled = obj.optBoolean("isEnabled", true),
                    targetBrightnessPercent = obj.optInt("targetBrightnessPercent", 30),
                    blockAutoFlare = obj.optBoolean("blockAutoFlare", true),
                    enableSubZeroFilter = obj.optBoolean("enableSubZeroFilter", true),
                    subZeroOpacity = obj.optDouble("subZeroOpacity", 0.40).toFloat()
                )
                map[rule.packageName] = rule
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun applyFilterOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = windowManager ?: return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val argb = currentColor.toInt()
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        val alphaInt = (currentOpacity.coerceIn(0.05f, 0.90f) * 255).toInt()
        val overlayColor = Color.argb(alphaInt, r, g, b)

        if (filterOverlayView == null) {
            val view = View(this).apply {
                setBackgroundColor(overlayColor)
                fitsSystemWindows = false
                @Suppress("DEPRECATION")
                systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
            try {
                wm.addView(view, params)
                filterOverlayView = view
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            filterOverlayView?.setBackgroundColor(overlayColor)
            try {
                wm.updateViewLayout(filterOverlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeFilterOverlay() {
        filterOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            filterOverlayView = null
        }
    }

    private fun setSystemBrightness(value: Int) {
        val clamped = value.coerceIn(5, 255)
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clamped
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Brightness Override Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors foreground apps and applies brightness & optical overrides"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val closeIntent = Intent(this, BrightnessOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val closePending = PendingIntent.getService(this, 5, closeIntent, PendingIntent.FLAG_IMMUTABLE)

        val title = if (activeOverriddenApp != null) {
            "Override Active: $activeOverriddenApp"
        } else {
            "App Brightness Override Active"
        }

        val text = if (activeOverriddenApp != null) {
            "Optical Dimmer applied (${(currentOpacity * 100).toInt()}% dim)"
        } else {
            "Monitoring foreground apps"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", closePending)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeFilterOverlay()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "brightness_override_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_FILTER = "com.example.service.START_FILTER"
        const val ACTION_STOP_FILTER = "com.example.service.STOP_FILTER"
        const val ACTION_UPDATE_STATE = "com.example.service.UPDATE_STATE"
        const val ACTION_STOP_SERVICE = "com.example.service.STOP_SERVICE"

        const val EXTRA_OPACITY = "extra_opacity"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_FILTER_ACTIVE = "extra_filter_active"
    }
}
