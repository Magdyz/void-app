package com.void.slate.storage.impl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * SQLCipher-based implementation of SecureStorage.
 *
 * Features:
 * - Database encrypted with SQLCipher (AES-256)
 * - Database key stored in Android Keystore (hardware-backed when available)
 * - All operations are suspend functions for coroutine safety
 * - Key-value storage with binary and string support
 */
class SqlCipherStorage(
    private val context: Context,
    private val keyAlias: String = "void_storage_key"
) : SecureStorage {

    private val database: SQLiteDatabase by lazy {
        val helper = VoidDatabaseHelper(context, getOrCreateDatabaseKey())
        helper.writableDatabase
    }

    init {
        // Initialize SQLCipher
        SQLiteDatabase.loadLibs(context)
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
     * Get or create the database encryption key from Android Keystore.
     */
    private fun getOrCreateDatabaseKey(): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val secretKey = if (keyStore.containsAlias(keyAlias)) {
            // Key exists, retrieve it
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            // Create new key
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

        // Derive a passphrase from the key
        // SQLCipher needs a string passphrase, so we hash the key bytes
        val keyBytes = secretKey.encoded
        return MessageDigest.getInstance("SHA-256").digest(keyBytes)
    }

    /**
     * SQLCipher database helper.
     */
    private class VoidDatabaseHelper(
        context: Context,
        private val key: ByteArray
    ) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE secure_storage (
                    key TEXT PRIMARY KEY,
                    value BLOB NOT NULL,
                    type TEXT NOT NULL,
                    created_at INTEGER DEFAULT (strftime('%s', 'now')),
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                )
                """.trimIndent()
            )

            // Index for faster lookups
            db.execSQL("CREATE INDEX idx_key ON secure_storage(key)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations go here
        }

        override fun getWritableDatabase(): SQLiteDatabase {
            return getWritableDatabase(key.toHexString())
        }

        override fun getReadableDatabase(): SQLiteDatabase {
            return getReadableDatabase(key.toHexString())
        }

        private fun ByteArray.toHexString(): String {
            return joinToString("") { "%02x".format(it) }
        }
    }

    companion object {
        private const val DATABASE_NAME = "void_secure.db"
        private const val DATABASE_VERSION = 1

        private const val TYPE_BINARY = "binary"
        private const val TYPE_STRING = "string"
    }
}
