package com.raynoxu.p2pfileserver

import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted backup of the identity key, so a server can keep its
 * id (and every shared link) when moving to another phone.
 *
 * File layout (all integers big-endian):
 *   "P2PKEY" 0x01            magic + format version   (7 bytes)
 *   kdf: u8                   1 = PBKDF2-HMAC-SHA256, 2 = PBKDF2-HMAC-SHA1
 *   iterations: u32
 *   salt: 16 bytes
 *   nonce: 12 bytes
 *   AES-256-GCM(key file bytes), header above as associated data
 *
 * SHA-1 is only used on Android < 8, which lacks PBKDF2WithHmacSHA256; it
 * gets more iterations to compensate (PBKDF2-HMAC-SHA1 itself is not broken).
 */
object IdentityBackup {
    private val MAGIC = byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 'Y'.code.toByte(), 1)
    private const val KDF_SHA256: Byte = 1
    private const val KDF_SHA1: Byte = 2
    private const val HEADER = 7 + 1 + 4 + 16 + 12
    const val MAX_FILE = 16 * 1024
    const val MIN_PASSPHRASE = 8

    class WrongPassphrase : Exception("Wrong passphrase, or the file is damaged")

    fun encrypt(key: ByteArray, passphrase: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val (kdf, iterations) = if (Build.VERSION.SDK_INT >= 26) KDF_SHA256 to 600_000 else KDF_SHA1 to 1_300_000
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(kdf).putInt(iterations).put(salt).put(nonce).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt, kdf, iterations), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        return ByteArrayOutputStream().apply { write(header); write(c.doFinal(key)) }.toByteArray()
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        if (file.size <= HEADER + 16 || !file.copyOfRange(0, 7).contentEquals(MAGIC)) {
            throw IllegalArgumentException("Not a server identity backup file")
        }
        val b = ByteBuffer.wrap(file)
        b.position(7)
        val kdf = b.get()
        val iterations = b.getInt()
        if (kdf != KDF_SHA256 && kdf != KDF_SHA1 || iterations !in 10_000..10_000_000) {
            throw IllegalArgumentException("Unsupported backup file")
        }
        val salt = ByteArray(16).also { b.get(it) }
        val nonce = ByteArray(12).also { b.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(passphrase, salt, kdf, iterations), GCMParameterSpec(128, nonce))
        c.updateAAD(file, 0, HEADER)
        return try {
            c.doFinal(file, HEADER, file.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, kdf: Byte, iterations: Int): SecretKeySpec {
        val alg = if (kdf == KDF_SHA256) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
