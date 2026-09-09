package com.rohit.sosafe.data.supabase

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realtime WebSocket listener client for Supabase Realtime service.
 * Allows subscribing to INSERT/UPDATE/DELETE events on PostgreSQL tables.
 */
class SupabaseRealtimeClient(
    private val tableName: String,
    private val filterColumn: String? = null,
    private val filterValue: String? = null,
    private val onEvent: (eventType: String, record: JSONObject) -> Unit
) {
    private val TAG = "SupabaseRealtime"
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var refCount = 1

    fun start() {
        val wsUrl = SupabaseApi.url
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/realtime/v1/websocket?apikey=${SupabaseApi.key}&vsn=1.0.0"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = SupabaseApi.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d(TAG, "Connected to Supabase Realtime for table: $tableName")

                // Join topic channel
                val joinTopic = "realtime:public:$tableName"
                val joinMsg = JSONObject().apply {
                    put("topic", joinTopic)
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("postgres_changes", JSONArray().apply {
                                val changeObj = JSONObject().apply {
                                    put("event", "*")
                                    put("schema", "public")
                                    put("table", tableName)
                                    if (filterColumn != null && filterValue != null) {
                                        put("filter", "$filterColumn=eq.$filterValue")
                                    }
                                }
                                put(changeObj)
                            })
                        })
                    })
                    put("ref", (refCount++).toString())
                }
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    val payload = json.optJSONObject("payload") ?: return

                    if (event == "postgres_changes") {
                        val data = payload.optJSONObject("data") ?: return
                        val type = data.optString("type")
                        val record = data.optJSONObject("record") ?: data.optJSONObject("old") ?: JSONObject()
                        onEvent(type, record)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing realtime message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Realtime WebSocket failure for $tableName: ${t.message}")
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
        })
    }

    fun stop() {
        try {
            webSocket?.close(1000, "Closed manually")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
    }
}
