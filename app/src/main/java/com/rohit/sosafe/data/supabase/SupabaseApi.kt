package com.rohit.sosafe.data.supabase

import android.util.Log
import com.rohit.sosafe.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight, robust Supabase REST API & Realtime Helper.
 * Handles database operations against PostgreSQL tables using standard HTTP/REST endpoints.
 */
object SupabaseApi {

    private const val TAG = "SupabaseApi"
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key: String = BuildConfig.SUPABASE_KEY.trim()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(endpoint: String): Request.Builder {
        return Request.Builder()
            .url("$url/rest/v1/$endpoint")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
    }

    /**
     * SELECT query returning a JSONArray of matching rows.
     */
    fun select(table: String, queryParams: String = ""): JSONArray {
        val endpoint = if (queryParams.isBlank()) table else "$table?$queryParams"
        val request = buildRequest(endpoint).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "[]"
            if (!response.isSuccessful) {
                Log.e(TAG, "SELECT error on $table: ${response.code} $body")
                return JSONArray()
            }
            return try {
                JSONArray(body)
            } catch (e: Exception) {
                JSONArray()
            }
        }
    }

    /**
     * UPSERT / INSERT a JSON object into a table.
     */
    fun upsert(table: String, json: JSONObject, onConflict: String = ""): Boolean {
        var endpoint = table
        if (onConflict.isNotBlank()) {
            endpoint += "?on_conflict=$onConflict"
        }
        val request = buildRequest(endpoint)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "UPSERT error on $table: ${response.code} ${response.body?.string()}")
                return false
            }
            return true
        }
    }

    /**
     * UPDATE matching rows in a table.
     */
    fun update(table: String, queryParams: String, json: JSONObject): Boolean {
        val request = buildRequest("$table?$queryParams")
            .header("Prefer", "return=minimal")
            .patch(json.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "UPDATE error on $table: ${response.code} ${response.body?.string()}")
                return false
            }
            return true
        }
    }

    /**
     * DELETE matching rows in a table.
     */
    fun delete(table: String, queryParams: String): Boolean {
        val request = buildRequest("$table?$queryParams")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "DELETE error on $table: ${response.code} ${response.body?.string()}")
                return false
            }
            return true
        }
    }
}
