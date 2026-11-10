package com.raynoxu1337.p2pfileserver

import android.content.Context
import android.os.Build
import android.os.Environment
import java.net.URI
import java.security.SecureRandom

/** User settings, stored in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Custom resolver page URL; "" means use [DEFAULT_RESOLVER]. */
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

    /** Soft limit of libp2p peer connections (connection manager high watermark). */
    var maxPeers: Int
        get() = sp.getInt("maxPeers", DEFAULT_MAX_PEERS)
        set(v) = sp.edit().putInt("maxPeers", v).apply()

    /** DHT client only (default). Off = also act as a DHT server, which costs more data. */
    var peerOnly: Boolean
        get() = sp.getBoolean("peerOnly", true)
        set(v) = sp.edit().putBoolean("peerOnly", v).apply()

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

    /** True when the user typed a custom resolver URL that can't be parsed (the default is used then). */
    val resolverOverrideInvalid: Boolean
        get() = resolverUrl.isNotBlank() && normalizeResolver(resolverUrl).isEmpty()

    /** Resolver page in use, ending in "/": the user's override if valid, else [DEFAULT_RESOLVER]. */
    val resolverBase: String
        get() = normalizeResolver(resolverUrl).ifEmpty { DEFAULT_RESOLVER }

    /**
     * URL path filebrowser is served under. The browser page proxies
     * "<resolver dir>/fs/" so both must agree.
     */
    val webBasePath: String
        get() = URI(resolverBase).rawPath + "fs/"

    fun shareLink(id: String = peerId): String =
        if (id.isEmpty()) "" else "${resolverBase}?server_name=$id"

    companion object {
        const val DEFAULT_MAX_PEERS = 120
        val MAX_PEERS_RANGE = 10..1000

        /** Public resolver page, published from web/p2p_resolver by .github/workflows/pages.yml. */
        const val DEFAULT_RESOLVER = "https://raynoxu1337.github.io/p2p_fileserver/"

        /** Resolver URL normalised to end in "/", or "" if blank / invalid. */
        fun normalizeResolver(url: String): String {
            val raw = url.trim().substringBefore('?').substringBefore('#')
            if (raw.isBlank()) return ""
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val u = runCatching { URI(withScheme) }.getOrNull() ?: return ""
            if (u.host.isNullOrBlank()) return ""
            var path = u.rawPath?.ifEmpty { "/" } ?: "/"
            if (path.endsWith("/index.html")) path = path.removeSuffix("index.html")
            if (!path.endsWith("/")) path += "/"
            return "${u.scheme}://${u.rawAuthority}$path"
        }

        fun newPassword(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            return (1..14).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
