package com.example.sensor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.InstalledAppInfo

object AppUsageHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundApp(context: Context): Pair<String, String>? {
        if (!hasUsageStatsPermission(context)) return null
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 15000, time)
        val event = UsageEvents.Event()
        var lastPkg: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            }
        }

        if (lastPkg == null) {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
            val sorted = stats.filter { it.lastTimeUsed > 0 }.maxByOrNull { it.lastTimeUsed }
            lastPkg = sorted?.packageName
        }

        if (lastPkg != null) {
            val pm = context.packageManager
            val label = try {
                val appInfo = pm.getApplicationInfo(lastPkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                lastPkg
            }
            return Pair(lastPkg, label)
        }
        return null
    }

    fun getInstalledLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val list = mutableListOf<InstalledAppInfo>()
        val seen = mutableSetOf<String>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (seen.add(pkg) && pkg != context.packageName) {
                val name = ri.loadLabel(pm).toString()
                val isSys = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(InstalledAppInfo(packageName = pkg, appName = name, isSystemApp = isSys))
            }
        }

        // Fallback or popular sample packages if simulator has few installed launchables
        val popularDefaults = listOf(
            InstalledAppInfo("com.walmart.android", "Walmart (Walmart Pay / Scan & Go)", false),
            InstalledAppInfo("com.target.ui", "Target (Wallet & Barcode)", false),
            InstalledAppInfo("com.starbucks.mobilecard", "Starbucks (Pay Barcode)", false),
            InstalledAppInfo("com.google.android.apps.walletnfcrel", "Google Wallet", false),
            InstalledAppInfo("com.google.android.youtube", "YouTube", false),
            InstalledAppInfo("com.google.android.apps.photos", "Google Photos", false),
            InstalledAppInfo("com.android.camera2", "Camera", true),
            InstalledAppInfo("com.google.android.apps.books", "Google Play Books", false),
            InstalledAppInfo("com.android.chrome", "Chrome Browser", false),
            InstalledAppInfo("com.whatsapp", "WhatsApp / Scanner", false),
            InstalledAppInfo("com.instagram.android", "Instagram", false)
        )

        for (pop in popularDefaults) {
            if (seen.add(pop.packageName)) {
                list.add(pop)
            }
        }

        return list.sortedBy { it.appName.lowercase() }
    }
}
