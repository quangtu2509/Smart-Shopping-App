package com.toked.smartshopping.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    suspend fun save(session: AuthSession)
    suspend fun load(): AuthSession?
    suspend fun clear()
}

class SecureSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "auth_session.enc"))
    private val alias = "smart_shopping_auth_v1"
    private val signedOut = AtomicFile(File(context.applicationContext.noBackupFilesDir, "auth_signed_out"))

    override suspend fun save(session: AuthSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = AuthCodec.encode(session)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(encrypted)
            file.finishWrite(stream)
            signedOut.delete()
            check(!signedOut.baseFile.exists()) { "Cannot activate saved session" }
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    override suspend fun load(): AuthSession? = withContext(Dispatchers.IO) {
        if (signedOut.baseFile.exists()) return@withContext null
        val bytes = try { file.openRead().use { it.readBytes() } }
            catch (_: java.io.FileNotFoundException) { return@withContext null }
        require(bytes.size in 30..65536 && bytes[0] == 1.toByte()) { "Invalid encrypted session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try { AuthCodec.decode(plain) } finally { plain.fill(0) }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // Persist sign-out before removing credentials, so a crash cannot resurrect the old session.
        val stream = signedOut.startWrite()
        try { stream.write(1); signedOut.finishWrite(stream) }
        catch (failure: Exception) { signedOut.failWrite(stream); throw failure }
        file.delete()
        check(!file.baseFile.exists()) { "Cannot remove encrypted credentials" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
