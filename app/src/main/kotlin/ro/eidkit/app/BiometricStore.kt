package ro.eidkit.app

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

private const val PREFS_NAME      = "bio_credentials"
private const val KEY_CAN         = "bio_can"
private const val KEY_PIN         = "bio_pin"
private const val KEY_PIN2        = "bio_pin2"
private const val PREFS_PLAIN     = "bio_prefs"
private const val KEY_NEVER_ASK   = "never_ask"

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
 * Stores CAN / auth PIN / signing PIN encrypted under Android Keystore,
 * requiring biometric authentication to read or write.
 */
object BiometricStore {

    /** True if the device supports biometrics and at least one value is stored. */
    fun hasCredentials(context: Context): Boolean {
        val mgr = BiometricManager.from(context)
        if (mgr.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return false
        return try {
            openPrefs(context).contains(KEY_CAN) ||
            openPrefs(context).contains(KEY_PIN) ||
            openPrefs(context).contains(KEY_PIN2)
        } catch (_: Exception) { false }
    }

    /**
     * Shows a biometric prompt and, on success, reads stored credentials.
     * Values not previously stored are null.
     */
    fun load(
        activity: FragmentActivity,
        onSuccess: (can: String?, pin: String?, pin2: String?) -> Unit,
        onFail: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    val prefs = openPrefs(activity)
                    onSuccess(
                        prefs.getString(KEY_CAN,  null),
                        prefs.getString(KEY_PIN,  null),
                        prefs.getString(KEY_PIN2, null),
                    )
                } catch (_: Exception) { onFail() }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
            override fun onAuthenticationFailed() = onFail()
        }).authenticate(promptInfo(activity))
    }

    /**
     * Shows a biometric prompt and, on success, applies [StoreOp] to each key.
     * [StoreOp.Write] with non-null stores the value; with null deletes the key.
     * [StoreOp.Skip] leaves the key exactly as it is.
     */
    fun save(
        activity: FragmentActivity,
        can: StoreOp,
        pin: StoreOp,
        pin2: StoreOp,
        onDone: () -> Unit,
    ) {
        val prefs = try {
            openPrefs(activity)
        } catch (e: Exception) {
            onDone(); return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    prefs.edit().apply {
                        applyOp(KEY_CAN,  can)
                        applyOp(KEY_PIN,  pin)
                        applyOp(KEY_PIN2, pin2)
                        apply()
                    }
                } catch (_: Exception) {}
                onDone()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone()
            override fun onAuthenticationFailed() {}
        }).authenticate(promptInfo(activity))
    }

    fun neverAsk(context: Context): Boolean =
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .getBoolean(KEY_NEVER_ASK, false)

    fun setNeverAsk(context: Context) {
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NEVER_ASK, true).apply()
    }

    /** Deletes all stored values and resets the never-ask flag. */
    fun clear(context: Context) {
        try { openPrefs(context).edit().clear().apply() } catch (_: Exception) {}
        context.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            .edit().remove(KEY_NEVER_ASK).apply()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun openPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun SharedPreferences.Editor.applyOp(key: String, op: StoreOp) {
        if (op is StoreOp.Write) {
            if (op.value != null) putString(key, op.value) else remove(key)
        }
    }

    private fun promptInfo(context: Context) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.bio_prompt_title))
        .setSubtitle(context.getString(R.string.bio_prompt_subtitle))
        .setNegativeButtonText(context.getString(R.string.bio_save_no))
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
}
