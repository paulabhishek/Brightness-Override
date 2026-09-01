package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppBrightnessRule
import com.example.data.BrightnessState
import com.example.data.InstalledAppInfo
import com.example.sensor.AppUsageHelper
import com.example.service.BrightnessOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BrightnessViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("brightness_override_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(BrightnessState())
    val uiState: StateFlow<BrightnessState> = _uiState.asStateFlow()

    private val _appRules = MutableStateFlow<Map<String, AppBrightnessRule>>(emptyMap())
    val appRules: StateFlow<Map<String, AppBrightnessRule>> = _appRules.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private var wasAutoDimmedByRule = false

    init {
        loadAppRules()
        loadInstalledApps()
        refreshPermissions()
        startForegroundWatchdog()
        ensureServiceStartedIfPermitted()
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        val canWrite = Settings.System.canWrite(context)
        val canOverlay = Settings.canDrawOverlays(context)
        val hasUsage = AppUsageHelper.hasUsageStatsPermission(context)

        _uiState.update {
            it.copy(
                canWriteSystemSettings = canWrite,
                canDrawOverlays = canOverlay,
                hasUsageAccess = hasUsage
            )
        }
        if (canOverlay) {
            ensureServiceStartedIfPermitted()
        }
    }

    private fun ensureServiceStartedIfPermitted() {
        val context = getApplication<Application>()
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, BrightnessOverlayService::class.java).apply {
                action = BrightnessOverlayService.ACTION_UPDATE_STATE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startForegroundWatchdog() {
        viewModelScope.launch(Dispatchers.Default) {
            var lastPkg: String? = null
            while (isActive) {
                delay(800)
                val context = getApplication<Application>()
                if (AppUsageHelper.hasUsageStatsPermission(context)) {
                    val foreground = AppUsageHelper.getForegroundApp(context)
                    if (foreground != null) {
                        val (pkg, name) = foreground
                        if (pkg != lastPkg) {
                            lastPkg = pkg
                            withContext(Dispatchers.Main) {
                                _uiState.update {
                                    it.copy(
                                        currentForegroundPackage = pkg,
                                        currentForegroundAppName = name
                                    )
                                }
                                onForegroundAppChanged(pkg, name)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onForegroundAppChanged(packageName: String, appName: String) {
        val rule = _appRules.value[packageName]
        if (rule != null && rule.isEnabled) {
            if (rule.enableSubZeroFilter) {
                applyOverlayDimmer(rule.subZeroOpacity)
                wasAutoDimmedByRule = true
            } else if (wasAutoDimmedByRule) {
                removeOverlayDimmer()
                wasAutoDimmedByRule = false
            }

            if (_uiState.value.canWriteSystemSettings) {
                setSystemBrightnessPercent(rule.targetBrightnessPercent)
            }
        } else if (wasAutoDimmedByRule) {
            removeOverlayDimmer()
            wasAutoDimmedByRule = false
        }
    }

    private fun applyOverlayDimmer(opacity: Float) {
        val context = getApplication<Application>()
        if (!Settings.canDrawOverlays(context)) return

        val intent = Intent(context, BrightnessOverlayService::class.java).apply {
            action = BrightnessOverlayService.ACTION_START_FILTER
            putExtra(BrightnessOverlayService.EXTRA_OPACITY, opacity)
            putExtra(BrightnessOverlayService.EXTRA_COLOR, 0xFF000000)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayDimmer() {
        val context = getApplication<Application>()
        val intent = Intent(context, BrightnessOverlayService::class.java).apply {
            action = BrightnessOverlayService.ACTION_STOP_FILTER
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setSystemBrightnessPercent(percent: Int) {
        val context = getApplication<Application>()
        if (Settings.System.canWrite(context)) {
            val clampedVal = ((percent.coerceIn(0, 100) / 100f) * 255).toInt().coerceIn(5, 255)
            try {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clampedVal
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveAppRule(rule: AppBrightnessRule) {
        val current = _appRules.value.toMutableMap()
        current[rule.packageName] = rule
        _appRules.value = current
        saveAppRulesToPrefs(current)
        syncInstalledAppsWithRules()
        ensureServiceStartedIfPermitted()
    }

    fun deleteAppRule(packageName: String) {
        val current = _appRules.value.toMutableMap()
        current.remove(packageName)
        _appRules.value = current
        saveAppRulesToPrefs(current)
        syncInstalledAppsWithRules()
    }

    fun toggleAppRule(packageName: String, enabled: Boolean) {
        val existing = _appRules.value[packageName]
        if (existing != null) {
            saveAppRule(existing.copy(isEnabled = enabled))
        } else {
            val app = _installedApps.value.find { it.packageName == packageName }
            if (app != null) {
                saveAppRule(
                    AppBrightnessRule(
                        packageName = packageName,
                        appName = app.appName,
                        isEnabled = enabled,
                        targetBrightnessPercent = 30,
                        enableSubZeroFilter = true,
                        subZeroOpacity = 0.40f
                    )
                )
            }
        }
    }

    private fun loadAppRules() {
        val jsonStr = prefs.getString("app_brightness_rules", null)
        val map = mutableMapOf<String, AppBrightnessRule>()

        if (jsonStr != null) {
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
        }

        // Add Walmart as a convenient default if no rules configured
        if (map.isEmpty()) {
            val walmartRule = AppBrightnessRule(
                packageName = "com.walmart.android",
                appName = "Walmart (Walmart Pay)",
                isEnabled = true,
                targetBrightnessPercent = 30,
                blockAutoFlare = true,
                enableSubZeroFilter = true,
                subZeroOpacity = 0.40f
            )
            map[walmartRule.packageName] = walmartRule
            saveAppRulesToPrefs(map)
        }

        _appRules.value = map
    }

    private fun saveAppRulesToPrefs(rules: Map<String, AppBrightnessRule>) {
        val jsonArray = JSONArray()
        rules.values.forEach { rule ->
            val obj = JSONObject().apply {
                put("packageName", rule.packageName)
                put("appName", rule.appName)
                put("isEnabled", rule.isEnabled)
                put("targetBrightnessPercent", rule.targetBrightnessPercent)
                put("blockAutoFlare", rule.blockAutoFlare)
                put("enableSubZeroFilter", rule.enableSubZeroFilter)
                put("subZeroOpacity", rule.subZeroOpacity.toDouble())
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("app_brightness_rules", jsonArray.toString()).apply()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = AppUsageHelper.getInstalledLaunchableApps(getApplication())
            withContext(Dispatchers.Main) {
                val rules = _appRules.value
                val merged = apps.map { app ->
                    val rule = rules[app.packageName]
                    app.copy(rule = rule)
                }
                _installedApps.value = merged
            }
        }
    }

    private fun syncInstalledAppsWithRules() {
        val rules = _appRules.value
        _installedApps.update { currentList ->
            currentList.map { app ->
                val rule = rules[app.packageName]
                app.copy(rule = rule)
            }
        }
    }
}
