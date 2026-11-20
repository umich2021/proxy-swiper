package com.autodate

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.autodate.agent.AgentService

/**
 * Turns the autoDate accessibility service back on after Android switches it off (it does that on
 * some app updates). Needs WRITE_SECURE_SETTINGS, which only adb can grant, once per install:
 *   adb shell pm grant com.autodate.app android.permission.WRITE_SECURE_SETTINGS
 * The grant survives updates; uninstalling removes it.
 */
object ServiceSwitch {

    fun canRestore(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context): Boolean {
        val me = ComponentName(context, AgentService::class.java)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    /** Adds our service to the enabled list (keeping any others). Returns true if it was off and is now on. */
    fun restore(context: Context): Boolean {
        if (isOn(context) || !canRestore(context)) return false
        val me = ComponentName(context, AgentService::class.java).flattenToString()
        val list = (enabledList(context) + me).joinToString(":")
        return runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
    }

    private fun enabledList(context: Context) =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }
}

/** Runs right after the app is updated, so the service comes back without opening the app. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ServiceSwitch.restore(context)
    }
}
