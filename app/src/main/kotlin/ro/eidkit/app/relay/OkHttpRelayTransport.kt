package ro.eidkit.app.relay

import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ro.eidkit.sdk.relay.NfcRelayTransport
import java.util.concurrent.TimeUnit

class OkHttpRelayTransport : NfcRelayTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null

    override suspend fun connect(url: String, onFrame: (String) -> Unit) {
        val connected = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                onFrame(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.completeExceptionally(t)
            }
        })
        connected.await()
    }

    override fun sendFrame(json: String) {
        ws?.send(json) ?: throw IllegalStateException("WebSocket not connected")
    }

    override fun close() {
        ws?.close(1000, "ok")
        ws = null
    }
}
