package com.raynoxu1337.p2pfileserver

import android.content.Context
import android.os.Build
import android.os.Environment
import java.net.URI
import java.security.SecureRandom

/** User settings, stored in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var resolverUrl: String
        get() = sp.getString("resolverUrl", "") ?: ""
        set(v) = sp.edit().putString("resolverUrl", v.trim()).apply()

    var sharedFolder: String
        get() = sp.getString("sharedFolder", null) ?: Environment.getExternalStorageDirectory().path
        set(v) = sp.edit().putString("sharedFolder", v.trim()).apply()

    var serverName: String
        get() = sp.getString("serverName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(v) = sp.edit().putString("serverName", v.trim()).apply()

    var adminUser: String
        get() = sp.getString("adminUser", null) ?: "admin"
        set(v) = sp.edit().putString("adminUser", v.trim()).apply()

    var adminPassword: String
        get() = sp.getString("adminPassword", null) ?: newPassword().also { adminPassword = it }
        set(v) = sp.edit().putString("adminPassword", v).apply()

    var readOnly: Boolean
        get() = sp.getBoolean("readOnly", false)
        set(v) = sp.edit().putBoolean("readOnly", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    /** Remembered so the service comes back after reboot/app update if it was on. */
    var wantRunning: Boolean
        get() = sp.getBoolean("wantRunning", false)
        set(v) = sp.edit().putBoolean("wantRunning", v).apply()

    var p2pPort: Int
        get() = sp.getInt("p2pPort", 4001)
        set(v) = sp.edit().putInt("p2pPort", v).apply()

    var turnUrl: String
        get() = sp.getString("turnUrl", "") ?: ""
        set(v) = sp.edit().putString("turnUrl", v.trim()).apply()

    var turnUser: String
        get() = sp.getString("turnUser", "") ?: ""
        set(v) = sp.edit().putString("turnUser", v.trim()).apply()

    var turnPass: String
        get() = sp.getString("turnPass", "") ?: ""
        set(v) = sp.edit().putString("turnPass", v).apply()

    var peerId: String
        get() = sp.getString("peerId", "") ?: ""
        set(v) = sp.edit().putString("peerId", v).apply()

    /** Resolver URL normalised to end in "/", or "" if not set / invalid. */
    val resolverBase: String
        get() {
            val raw = resolverUrl.substringBefore('?').substringBefore('#')
            if (raw.isBlank()) return ""
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val u = runCatching { URI(withScheme) }.getOrNull() ?: return ""
            if (u.host.isNullOrBlank()) return ""
            var path = u.rawPath ?: "/"
            if (path.endsWith("/index.html")) path = path.removeSuffix("index.html")
            if (!path.endsWith("/")) path += "/"
            return "${u.scheme}://${u.rawAuthority}$path"
        }

    /**
     * URL path filebrowser is served under. The browser page proxies
     * "<resolver dir>/fs/" so both must agree.
     */
    val webBasePath: String
        get() {
            val b = resolverBase
            val dir = if (b.isEmpty()) "/p2p_resolver/" else URI(b).rawPath
            return dir + "fs/"
        }

    fun shareLink(id: String = peerId): String =
        if (resolverBase.isEmpty() || id.isEmpty()) "" else "${resolverBase}?server_name=$id"

    companion object {
        fun newPassword(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            return (1..14).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
