package com.camimu.orient.ar

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk

/** ARCore support/installation checks. */
object ArCoreAvailability {
    enum class Status { SUPPORTED, NOT_INSTALLED, UNSUPPORTED, CHECKING }

    fun check(context: Context): Status {
        val a = runCatching { ArCoreApk.getInstance().checkAvailability(context) }.getOrNull() ?: return Status.UNSUPPORTED
        return when {
            a.isTransient -> Status.CHECKING
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> Status.SUPPORTED
            a.isSupported -> Status.NOT_INSTALLED
            else -> Status.UNSUPPORTED
        }
    }

    /** @return true if ARCore is installed and ready; false if an install was requested (the activity pauses). */
    fun ensureInstalled(activity: Activity, userRequestedInstall: Boolean): Boolean = try {
        ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall) == ArCoreApk.InstallStatus.INSTALLED
    } catch (e: Exception) {
        Log.w("CamIMU.Ar", "ARCore install failed: ${e.message}")
        false
    }
}
