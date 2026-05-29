package ro.eidkit.app

import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG               = "BiometricStore"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS         = "eidkit_bio_key"
private const val PREFS_NAME        = "bio_credentials"
private const val KEY_BLOB          = "bio_blob"       // single encrypted JSON
private const val PREFS_PLAIN       = "bio_prefs"
private const val KEY_NEVER_ASK     = "never_ask"
private const val KEY_FIELDS        = "saved_fields"   // comma-separated set: "can,pin,pin2"
private const val GCM_TAG_LENGTH    = 128

/**
 * Wraps a value to write to the store.
 * - [Write] with a non-null string → store that value
 * - [Write] with null             → delete the key
 * - [Skip]                        → leave the key untouched
 */
sealed class StoreOp {
    data class Write(val value: String?) : StoreOp()
    object Skip : StoreOp()
}

/**
 * Stores CAN / auth PIN / signing PIN as a single AES-256-GCM encrypted JSON blob
 * under an Android Keystore key that requires biometric authentication per operation.
 * Per-operation auth means only the cipher passed to BiometricPrompt.CryptoObject is
 * unlocked — so all values are encrypted/decrypted in one cipher.doFinal() call.
 */
object BiometricStore {

    /** True if the device has a usable strong biometric sensor. */
    fun canSave(context: Context): Boolean {
        val result = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
        logd("canAuthenticate=$result (SUCCESS=0, ERROR_NONE_ENROLLED=11, ERROR_HW_UNAVAILABLE=1, ERROR_NO_HARDWARE=12, STATUS_UNKNOWN=21)")
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** True if the device supports biometrics and an encrypted blob is stored. */
    fun hasCredentials(context: Context): Boolean {
        if (!canSave(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = prefs.contains(KEY_BLOB)
        logd("hasCredentials=$result")
        return result
    }

    /**
     * Shows a biometric prompt and on success decrypts the stored blob.
     * Values not previously stored are null.
     */
    fun load(
        activity: FragmentActivity,
        onSuccess: (can: String?, pin: String?, pin2: String?) -> Unit,
        onFail: () -> Unit,
    ) {
        if (keystoreKey() == null) { onFail(); return }
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                logd("load: auth succeeded")
                val stored = prefs.getString(KEY_BLOB, null)
                if (stored == null) { onSuccess(null, null, null); return }
                try {
                    val key = keystoreKey() ?: run { onFail(); return }
                    val json = JSONObject(decryptWithKey(key, stored) ?: throw Exception("decrypt returned null"))
                    onSuccess(
                        json.optString("can",  "").ifEmpty { null },
                        json.optString("pin",  "").ifEmpty { null },
                        json.optString("pin2", "").ifEmpty { null },
                    )
                } catch (e: Exception) {
                    loge("load: decrypt failed: ${e.message}", e)
                    // Key invalidated — clean up so the prompt doesn't appear again
                    deleteKey()
                    prefs.edit().remove(KEY_BLOB).apply()
                    onFail()
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                loge("load: auth error $errorCode: $errString")
                onFail()
            }
            override fun onAuthenticationFailed() {
                logw("load: auth failed")
                onFail()
            }
        }).authenticate(promptInfo(activity))
    }

    /**
     * Shows a biometric prompt and on success merges [StoreOp]s into the stored blob
     * and re-encrypts it with a single cipher operation.
     */
    fun save(
        activity: FragmentActivity,
        can: StoreOp,
        pin: StoreOp,
        pin2: StoreOp,
        onDone: () -> Unit,
    ) {
        try { getOrCreateKey() } catch (e: Exception) {
            loge("save: key creation failed: ${e.message}", e)
            onDone(); return
        }
        logd("save: showing prompt")
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                logd("save: auth succeeded")
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                try {
                    // Read existing blob to honour Skip ops — key is available for 5s after auth
                    val existing = prefs.getString(KEY_BLOB, null)
                        ?.let { runCatching { JSONObject(decryptWithKey(keystoreKey()!!, it)) }.getOrNull() }
                        ?: JSONObject()
                    val json = JSONObject().apply {
                        put("can",  resolveOp(can,  existing.optString("can",  "").ifEmpty { null }))
                        put("pin",  resolveOp(pin,  existing.optString("pin",  "").ifEmpty { null }))
                        put("pin2", resolveOp(pin2, existing.optString("pin2", "").ifEmpty { null }))
                    }
                    // Re-encrypt with a fresh cipher — key is unlocked for the 5s window
                    val freshCipher = Cipher.getInstance("AES/GCM/NoPadding")
                        .also { it.init(Cipher.ENCRYPT_MODE, keystoreKey()!!) }
                    val encrypted = encryptWithCipher(freshCipher, json.toString())
                    prefs.edit().putString(KEY_BLOB, encrypted).apply()
                    // Update shadow field index so savedFields() works without auth
                    val fields = listOfNotNull(
                        "can".takeIf  { json.optString("can",  "").isNotEmpty() },
                        "pin".takeIf  { json.optString("pin",  "").isNotEmpty() },
                        "pin2".takeIf { json.optString("pin2", "").isNotEmpty() },
                    )
                    activity.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
                        .edit().putString(KEY_FIELDS, fields.joinToString(",")).apply()
                    logd("save: written successfully fields=$fields")
                } catch (e: Exception) {
                    loge("save: write failed: ${e.message}", e)
                    // Key was invalidated by new biometric enrollment — delete it so next save recreates it
                    deleteKey()
                    prefs.edit().remove(KEY_BLOB).apply()
                }
                onDone()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                loge("save: auth error $errorCode: $errString")
                onDone()
            }
            override fun onAuthenticationFailed() {
                logw("save: auth failed")
            }
        }).authenticate(promptInfo(activity))
    }

    fun neverAsk(context: Context): Boolean =
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEVER_ASK, false)

    fun setNeverAsk(context: Context) {
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NEVER_ASK, true).apply()
    }

    /** Returns which fields are currently saved (without requiring biometric auth). */
    fun savedFields(context: Context): Set<String> {
        if (!hasCredentials(context)) return emptySet()
        val raw = context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .getString(KEY_FIELDS, null)
        // Shadow index not yet written (credentials saved before this version) — assume all present
        if (raw.isNullOrBlank()) return setOf("can", "pin", "pin2")
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    /** Deletes all stored values and resets the never-ask flag. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .edit().remove(KEY_NEVER_ASK).remove(KEY_FIELDS).apply()
    }

    /** Clears a single field from the stored blob. Requires biometric auth. */
    fun clearField(activity: FragmentActivity, field: String, onDone: () -> Unit) {
        val fieldOp = StoreOp.Write(null)
        save(
            activity = activity,
            can      = if (field == "can")  fieldOp else StoreOp.Skip,
            pin      = if (field == "pin")  fieldOp else StoreOp.Skip,
            pin2     = if (field == "pin2") fieldOp else StoreOp.Skip,
            onDone   = onDone,
        )
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun logd(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }
    private fun loge(msg: String, t: Throwable? = null) { if (BuildConfig.DEBUG) Log.e(TAG, msg, t) }
    private fun logw(msg: String) { if (BuildConfig.DEBUG) Log.w(TAG, msg) }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            try {
                // Probe the key — init will throw if it was invalidated by new biometric enrollment
                val key = ks.getKey(KEY_ALIAS, null) as SecretKey
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
                return key
            } catch (_: Exception) {
                // Invalidated — delete and fall through to recreate
                ks.deleteEntry(KEY_ALIAS)
            }
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(5, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .also { it.init(spec) }
            .generateKey()
    }

    private fun keystoreKey(): SecretKey? =
        (KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
            .getKey(KEY_ALIAS, null) as? SecretKey)

    private fun encryptWithCipher(cipher: Cipher, value: String): String {
        val iv         = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    // Decrypt using a key retrieved from keystore (valid within biometric auth window).
    private fun decryptWithKey(key: SecretKey, stored: String): String? {
        return try {
            val parts      = stored.split(":")
            if (parts.size != 2) null
            else {
                val iv         = Base64.decode(parts[0], Base64.NO_WRAP)
                val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding")
                    .also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv)) }
                    .doFinal(ciphertext)
                    .toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { null }
    }

    private fun resolveOp(op: StoreOp, existing: String?): String? = when (op) {
        is StoreOp.Write -> op.value
        is StoreOp.Skip  -> existing
    }

    private fun promptInfo(context: Context) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.bio_prompt_title))
        .setSubtitle(context.getString(R.string.bio_prompt_subtitle))
        .setNegativeButtonText(context.getString(R.string.bio_save_no))
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
}
