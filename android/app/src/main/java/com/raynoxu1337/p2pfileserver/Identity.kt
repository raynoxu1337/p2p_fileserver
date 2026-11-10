package com.raynoxu1337.p2pfileserver

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The server's libp2p identity (Ed25519 key → PeerID, the "server id" in every
 * shared link). Nothing is built into the APK: a fresh key is generated on
 * first run and kept in app-private storage, and only replaced when the user
 * explicitly asks for a new identity.
 *
 * Key handling is delegated to the node binary (-gen-key / -print-id) so the
 * on-disk format is exactly what it loads.
 */
object Identity {
    fun keyFile(ctx: Context) = File(ctx.filesDir, "p2p/identity.key")

    /**
     * Returns the PeerID, generating the key first if this is the first run.
     * Blocking (runs the node binary); call off the main thread.
     */
    @Synchronized
    fun ensure(ctx: Context): String {
        val prefs = Prefs(ctx)
        val key = keyFile(ctx)
        val id = if (key.exists()) {
            prefs.peerId.ifEmpty { node(ctx, "-print-id") }
        } else {
            ServerState.log("app", "first run: generating server identity")
            node(ctx, "-gen-key")
        }
        publish(prefs, id)
        return id
    }

    /**
     * Throws away the current key and creates a new one. The old server id,
     * link and QR code stop working permanently. Blocking.
     */
    @Synchronized
    fun regenerate(ctx: Context): String {
        val id = node(ctx, "-gen-key")
        ServerState.log("app", "generated new server identity $id")
        publish(Prefs(ctx), id)
        return id
    }

    /** The raw key file, for an (encrypted) backup. */
    @Synchronized
    fun keyBytes(ctx: Context): ByteArray {
        ensure(ctx)
        return keyFile(ctx).readBytes()
    }

    /** PeerID of a key file's bytes, validated by the node itself. Blocking. */
    fun peerIdOf(ctx: Context, key: ByteArray): String {
        val tmp = File.createTempFile("restore", ".key", ctx.cacheDir)
        try {
            tmp.writeBytes(key)
            return node(ctx, "-print-id", tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Replaces the identity with a restored key (already validated with
     * [peerIdOf]). The current key is gone afterwards. Blocking.
     */
    @Synchronized
    fun install(ctx: Context, key: ByteArray): String {
        val id = peerIdOf(ctx, key)
        val f = keyFile(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(key)
        if (!tmp.renameTo(f)) error("could not replace the key file")
        ServerState.log("app", "restored server identity $id")
        publish(Prefs(ctx), id)
        return id
    }

    private fun publish(prefs: Prefs, id: String) {
        prefs.peerId = id
        ServerState.update { it.copy(peerId = id) }
    }

    private fun node(ctx: Context, flag: String, key: File = keyFile(ctx)): String {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libp2pnode.so")
        val proc = ProcessBuilder(bin.path, flag, "-key", key.path)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroy()
            error("p2pnode $flag timed out")
        }
        val id = out.lineSequence().lastOrNull()?.trim().orEmpty()
        if (proc.exitValue() != 0 || !id.startsWith("12D3")) error("p2pnode $flag failed: $out")
        return id
    }
}
