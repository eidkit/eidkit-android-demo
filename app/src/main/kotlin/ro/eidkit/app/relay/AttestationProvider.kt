package ro.eidkit.app.relay

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

private const val TAG = "Attestation"

class AttestationProvider(private val context: Context) {

    suspend fun getToken(nonce: String): String {
        val manager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()
        return manager.requestIntegrityToken(request).await().token()
    }
}

suspend fun performAttestation(
    provider: AttestationProvider,
    transport: OkHttpRelayTransport,
    receiveFrame: suspend () -> String,
) {
    val raw = receiveFrame()
    val frame = runCatching { JSONObject(raw) }.getOrNull()
    if (frame?.optString("type") != "attest_challenge") {
        Log.w(TAG, "expected attest_challenge, got: ${raw.take(120)}")
        return
    }
    val nonce = frame.getString("nonce")
    Log.d(TAG, "attest_challenge received, nonce=${nonce.take(16)}...")

    val token = runCatching { provider.getToken(nonce) }.getOrElse { e ->
        Log.w(TAG, "Play Integrity failed (${e.javaClass.simpleName}): ${e.message}")
        ""
    }

    transport.sendFrame(
        JSONObject()
            .put("type", "attest_token")
            .put("platform", "android")
            .put("token", token)
            .toString()
    )
    Log.d(TAG, "attest_token sent, token prefix=${token.take(20)}")
}
