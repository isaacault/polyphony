package app.polyphony.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 17's local network permission (also enforced early by some OSes such as GrapheneOS).
 * Without it every SSDP, mDNS and LAN socket is silently cut off, so Cast and DLNA discovery
 * see nothing while streaming from the internet keeps working.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** True when this OS knows the permission at all; older platforms never gate LAN access. */
    fun isEnforced(context: Context): Boolean =
        runCatching { context.packageManager.getPermissionInfo(PERMISSION, 0) }.isSuccess

    /** Granted, or not something this OS asks for. */
    fun isGranted(context: Context): Boolean =
        !isEnforced(context) || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Human-readable state for logs and the diagnostics export. */
    fun describe(context: Context): String = when {
        !isEnforced(context) -> "not enforced on this OS"
        isGranted(context) -> "granted"
        else -> "DENIED"
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
