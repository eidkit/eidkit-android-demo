package ro.eidkit.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "email_store"
private const val KEY_ENTRIES = "entries"

data class RememberedEmail(
    val clientId: String,
    val serviceName: String,
    val email: String,
)

object EmailStore {

    fun getRemembered(context: Context, clientId: String): String? {
        return all(context).firstOrNull { it.clientId == clientId }?.email
    }

    fun remember(context: Context, clientId: String, serviceName: String, email: String) {
        val list = all(context).toMutableList()
        list.removeAll { it.clientId == clientId }
        list.add(RememberedEmail(clientId, serviceName, email))
        save(context, list)
    }

    fun forget(context: Context, clientId: String) {
        val list = all(context).toMutableList()
        list.removeAll { it.clientId == clientId }
        save(context, list)
    }

    fun all(context: Context): List<RememberedEmail> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RememberedEmail(
                    clientId    = obj.getString("clientId"),
                    serviceName = obj.getString("serviceName"),
                    email       = obj.getString("email"),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, list: List<RememberedEmail>) {
        val arr = JSONArray()
        list.forEach { entry ->
            arr.put(JSONObject().apply {
                put("clientId",    entry.clientId)
                put("serviceName", entry.serviceName)
                put("email",       entry.email)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
