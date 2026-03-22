package com.rohit.sosafe.utils

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class CloudinaryUploader {

    // UPDATE THESE WITH YOUR CLOUDINARY CREDENTIALS
    private val CLOUD_NAME = "dgvzyzahf"
    private val UPLOAD_PRESET = "SoSafe"

    private val client = OkHttpClient()

    fun uploadAudio(
        file: File,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val url = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/raw/upload"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/aac".toMediaTypeOrNull()))
            .addFormDataPart("upload_preset", UPLOAD_PRESET)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("CloudinaryUploader", "Upload failed for ${file.name}", e)
                onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val errorMsg = response.body?.string() ?: "Unknown error"
                        Log.e("CloudinaryUploader", "Upload unsuccessful: $errorMsg")
                        onFailure(IOException("Unexpected code $response"))
                        return
                    }

                    val responseData = response.body?.string()
                    val json = JSONObject(responseData ?: "{}")
                    val secureUrl = json.optString("secure_url", "")
                    
                    if (secureUrl.isNotEmpty()) {
                        Log.d("CloudinaryUploader", "Upload successful: $secureUrl")
                        onSuccess(secureUrl)
                    } else {
                        onFailure(IOException("Secure URL not found in response"))
                    }
                }
            }
        })
    }
}