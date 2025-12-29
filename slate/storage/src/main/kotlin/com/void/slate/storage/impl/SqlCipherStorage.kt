package com.void.slate.storage.impl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher-based implementation of SecureStorage.
 *
 * Features:
 * - Database encrypted with SQLCipher (AES-256)
 * - Database key stored in Android Keystore (hardware-backed when available)
 * - All operations are suspend functions for coroutine safety
 * - Key-value storage with binary and string support
 */
@OptIn(ExperimentalStdlibApi::class)
class SqlCipherStorage(
    private val context: Context,
    private val keyAlias: String = "void_storage_key"
) : SecureStorage {

    init {
        // Initialize SQLCipher native library
        System.loadLibrary("sqlcipher")
    }

    private val database: SQLiteDatabase by lazy {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val password = getOrCreateDatabaseKey().toHexString()

        // Open or create encrypted database
        val db = SQLiteDatabase.openOrCreateDatabase(
            databaseFile.absolutePath,
            password,
            null,
            null,
            null
        )

        // Create tables if they don't exist
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS secure_storage (
                key TEXT PRIMARY KEY,
                value BLOB NOT NULL,
                type TEXT NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
            """.trimIndent()
        )

        // Create index for faster lookups
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_key ON secure_storage(key)")

        db
    }

    override suspend fun put(key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        database.execSQL(
            "INSERT OR REPLACE INTO secure_storage (key, value, type) VALUES (?, ?, ?)",
            arrayOf(key, value, TYPE_BINARY)
        )
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        database.rawQuery(
            "SELECT value FROM secure_storage WHERE key = ? AND type = ?",
            arrayOf(key, TYPE_BINARY)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getBlob(0)
            } else {
                null
            }
        }
    }

    override suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        database.execSQL(
            "INSERT OR REPLACE INTO secure_storage (key, value, type) VALUES (?, ?, ?)",
            arrayOf(key, value.toByteArray(Charsets.UTF_8), TYPE_STRING)
        )
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        database.rawQuery(
            "SELECT value FROM secure_storage WHERE key = ? AND type = ?",
            arrayOf(key, TYPE_STRING)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getBlob(0)?.toString(Charsets.UTF_8)
            } else {
                null
            }
        }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        database.execSQL(
            "DELETE FROM secure_storage WHERE key = ?",
            arrayOf(key)
        )
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        database.rawQuery(
            "SELECT 1 FROM secure_storage WHERE key = ? LIMIT 1",
            arrayOf(key)
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    override suspend fun getDeviceId(): ByteArray = withContext(Dispatchers.IO) {
        // Use a stable device identifier stored in the database
        val deviceIdKey = "__device_id__"
        get(deviceIdKey) ?: run {
            // Generate new device ID
            val newId = ByteArray(32)
            java.security.SecureRandom().nextBytes(newId)
            put(deviceIdKey, newId)
            newId
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        database.execSQL("DELETE FROM secure_storage")
    }

    /**
     * Get or create the database encryption key.
     * Uses Android Keystore to protect a randomly generated database key.
     */
    private fun getOrCreateDatabaseKey(): ByteArray {
        val prefs = context.getSharedPreferences("void_storage_prefs", Context.MODE_PRIVATE)
        val encryptedKeyBase64 = prefs.getString(PREF_ENCRYPTED_DB_KEY, null)
        val ivBase64 = prefs.getString(PREF_DB_KEY_IV, null)

        return if (encryptedKeyBase64 != null && ivBase64 != null) {
            // Try to decrypt existing database key
            try {
                val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
                decryptDatabaseKey(encryptedKey, iv)
            } catch (e: Exception) {
                // Decryption failed (e.g., Keystore key was deleted but encrypted key still exists)
                // This can happen after app reinstall or data clear
                Log.w(TAG, "Failed to decrypt existing database key, generating new one", e)

                // Clear old encrypted key and database
                prefs.edit()
                    .remove(PREF_ENCRYPTED_DB_KEY)
                    .remove(PREF_DB_KEY_IV)
                    .apply()

                // Delete old database file if it exists
                context.getDatabasePath(DATABASE_NAME)?.delete()

                // Generate new database key
                generateNewDatabaseKey()
            }
        } else {
            // Generate and encrypt new database key
            generateNewDatabaseKey()
        }
    }

    /**
     * Generate a new database key and encrypt it.
     */
    private fun generateNewDatabaseKey(): ByteArray {
        val prefs = context.getSharedPreferences("void_storage_prefs", Context.MODE_PRIVATE)
        val dbKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (encryptedKey, iv) = encryptDatabaseKey(dbKey)

        // Store encrypted key
        prefs.edit()
            .putString(PREF_ENCRYPTED_DB_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
            .putString(PREF_DB_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()

        return dbKey
    }

    /**
     * Encrypt the database key using Android Keystore.
     */
    private fun encryptDatabaseKey(dbKey: ByteArray): Pair<ByteArray, ByteArray> {
        val keystoreKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)

        val encryptedKey = cipher.doFinal(dbKey)
        val iv = cipher.iv

        return Pair(encryptedKey, iv)
    }

    /**
     * Decrypt the database key using Android Keystore.
     */
    private fun decryptDatabaseKey(encryptedKey: ByteArray, iv: ByteArray): ByteArray {
        val keystoreKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey, spec)

        return cipher.doFinal(encryptedKey)
    }

    /**
     * Get or create the Android Keystore key.
     */
    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        return if (keyStore.containsAlias(keyAlias)) {
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    /**
     * Convert byte array to hex string for use as database password.
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SqlCipherStorage"
        private const val DATABASE_NAME = "void_secure.db"
        private const val PREF_ENCRYPTED_DB_KEY = "encrypted_db_key"
        private const val PREF_DB_KEY_IV = "db_key_iv"

        private const val TYPE_BINARY = "binary"
        private const val TYPE_STRING = "string"
    }
}
