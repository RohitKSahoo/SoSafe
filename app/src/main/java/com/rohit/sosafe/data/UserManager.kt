package com.rohit.sosafe.data

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.rohit.sosafe.data.contracts.PairingRequest
import com.rohit.sosafe.data.contracts.RemovalNotification
import com.rohit.sosafe.data.contracts.SoSafeContract
import com.rohit.sosafe.data.supabase.SupabaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class UserManager(private val context: Context) {

    private val tag = "UserManager"
    private val userCodeKey = "user_code"
    private val prefsName = "sosafe_prefs"

    fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun getUserCodeSync(): String? {
        val sharedPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return sharedPrefs.getString(userCodeKey, null)
    }

    suspend fun getUserCode(): String {
        val sharedPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        var userCode = sharedPrefs.getString(userCodeKey, null)

        if (userCode == null) {
            userCode = generateUniqueUserCode()
            sharedPrefs.edit().putString(userCodeKey, userCode).apply()
            storeUserCodeInSupabase(userCode)
        } else {
            storeUserCodeInSupabase(userCode)
            updateFcmToken(userCode)
        }
        return userCode
    }

    private fun generateUniqueUserCode(): String {
        val length = 8
        val allowedChars = ('0'..'9') + ('A'..'Z')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    private suspend fun storeUserCodeInSupabase(userCode: String) = withContext(Dispatchers.IO) {
        try {
            val fcmToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                ""
            }

            val json = JSONObject().apply {
                put("user_id", userCode)
                put("contacts", JSONArray())
                put("contact_names", JSONObject())
                put("fcm_token", fcmToken)
                put("created_at", System.currentTimeMillis())
            }

            val success = SupabaseApi.upsert("users", json, onConflict = "user_id")
            if (success) {
                Log.d(tag, "User code '$userCode' stored in Supabase.")
            } else {
                Log.e(tag, "Error storing user code in Supabase.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception storing user code in Supabase: ${e.message}")
        }
    }

    suspend fun updateFcmToken(userCode: String) = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            val json = JSONObject().apply {
                put("fcm_token", token)
            }
            SupabaseApi.update("users", "user_id=eq.$userCode", json)
            Log.d(tag, "FCM Token updated successfully in Supabase")
        } catch (e: Exception) {
            Log.e(tag, "Failed to update FCM token: ${e.message}")
        }
    }

    /**
     * Validates if a target user code exists in Supabase and is not self.
     */
    suspend fun validateUserCode(inputCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val myCode = getUserCode()
        val formattedCode = inputCode.replace("-", "").trim().uppercase()

        if (formattedCode.length != 8) {
            return@withContext Result.failure(Exception("Code must be 8 characters long"))
        }

        if (myCode == formattedCode) {
            return@withContext Result.failure(Exception("Cannot link to your own device ID"))
        }

        try {
            val rows = SupabaseApi.select("users", "user_id=eq.$formattedCode")
            if (rows.length() == 0) {
                Result.failure(Exception("No such code exists"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error validating user code: ${e.message}")
            Result.failure(Exception("Failed to verify code: ${e.message}"))
        }
    }

    /**
     * Sends a 2-Step Pairing Request to a recipient device.
     */
    suspend fun sendPairingRequest(inputCode: String, customName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val myCode = getUserCode()
        val targetCode = inputCode.replace("-", "").trim().uppercase()

        val validation = validateUserCode(targetCode)
        if (validation.isFailure) return@withContext validation

        try {
            // Check if already in contacts list
            val rows = SupabaseApi.select("users", "user_id=eq.$myCode")
            if (rows.length() > 0) {
                val userObj = rows.getJSONObject(0)
                val contactsArr = userObj.optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until contactsArr.length()) {
                    if (contactsArr.getString(i) == targetCode) {
                        return@withContext Result.failure(Exception("Already linked to this user"))
                    }
                }
            }

            // Save target custom name locally on sender device
            updateContactName(targetCode, customName)

            val requestId = "${myCode}_${targetCode}"
            val json = JSONObject().apply {
                put("request_id", requestId)
                put("from_user_id", myCode)
                put("from_user_name", customName.ifBlank { "User $myCode" })
                put("to_user_id", targetCode)
                put("status", SoSafeContract.Status.PENDING)
                put("created_at", System.currentTimeMillis())
            }

            val ok = SupabaseApi.upsert("pairing_requests", json, onConflict = "request_id")
            if (ok) {
                Log.d(tag, "Pairing request sent from $myCode to $targetCode")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to send pairing request"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Error sending pairing request: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Accepts a pending pairing request and establishes symmetric link.
     */
    suspend fun acceptPairingRequest(requestId: String, fromUserId: String, customNameForFromUser: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        val myCode = getUserCode()
        try {
            if (customNameForFromUser.isNotBlank()) {
                updateContactName(fromUserId, customNameForFromUser)
            }

            // 1. Add fromUserId to my contacts
            addContactToUserList(myCode, fromUserId)
            // 2. Add myCode to fromUserId contacts
            addContactToUserList(fromUserId, myCode)

            // 3. Mark request status as ACCEPTED
            val statusJson = JSONObject().apply { put("status", SoSafeContract.Status.ACCEPTED) }
            SupabaseApi.update("pairing_requests", "request_id=eq.$requestId", statusJson)

            Log.d(tag, "Pairing request accepted between $myCode and $fromUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error accepting pairing request: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Declines a pending pairing request.
     */
    suspend fun declinePairingRequest(requestId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val statusJson = JSONObject().apply { put("status", SoSafeContract.Status.REJECTED) }
            SupabaseApi.update("pairing_requests", "request_id=eq.$requestId", statusJson)
            Log.d(tag, "Pairing request declined: $requestId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error declining pairing request: ${e.message}")
            Result.failure(e)
        }
    }

    private fun addContactToUserList(userId: String, contactCode: String) {
        val rows = SupabaseApi.select("users", "user_id=eq.$userId")
        if (rows.length() > 0) {
            val userObj = rows.getJSONObject(0)
            val contactsArr = userObj.optJSONArray("contacts") ?: JSONArray()
            var exists = false
            val newArr = JSONArray()
            for (i in 0 until contactsArr.length()) {
                val c = contactsArr.getString(i)
                newArr.put(c)
                if (c == contactCode) exists = true
            }
            if (!exists) newArr.put(contactCode)

            val updateObj = JSONObject().apply { put("contacts", newArr) }
            SupabaseApi.update("users", "user_id=eq.$userId", updateObj)
        }
    }

    private fun removeContactFromUserList(userId: String, contactCode: String) {
        val rows = SupabaseApi.select("users", "user_id=eq.$userId")
        if (rows.length() > 0) {
            val userObj = rows.getJSONObject(0)
            val contactsArr = userObj.optJSONArray("contacts") ?: JSONArray()
            val newArr = JSONArray()
            for (i in 0 until contactsArr.length()) {
                val c = contactsArr.getString(i)
                if (c != contactCode) {
                    newArr.put(c)
                }
            }
            val updateObj = JSONObject().apply { put("contacts", newArr) }
            SupabaseApi.update("users", "user_id=eq.$userId", updateObj)
        }
    }

    suspend fun updateContactName(contactId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val myCode = getUserCodeSync() ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val rows = SupabaseApi.select("users", "user_id=eq.$myCode")
            if (rows.length() > 0) {
                val userObj = rows.getJSONObject(0)
                val namesObj = userObj.optJSONObject("contact_names") ?: JSONObject()
                namesObj.put(contactId, name)

                val updateObj = JSONObject().apply { put("contact_names", namesObj) }
                SupabaseApi.update("users", "user_id=eq.$myCode", updateObj)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error updating contact name: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Removes contact symmetrically for both users and creates a removal notification for the target user.
     */
    suspend fun removeContact(targetCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val myCode = getUserCode()
        val formattedTargetCode = targetCode.replace("-", "").trim().uppercase()

        try {
            var myName = "User $myCode"
            val myRows = SupabaseApi.select("users", "user_id=eq.$myCode")
            if (myRows.length() > 0) {
                val namesObj = myRows.getJSONObject(0).optJSONObject("contact_names") ?: JSONObject()
                if (namesObj.has(myCode)) {
                    myName = namesObj.getString(myCode)
                }
            }

            // 1. Remove target from my contacts
            removeContactFromUserList(myCode, formattedTargetCode)
            // 2. Remove me from target contacts
            removeContactFromUserList(formattedTargetCode, myCode)

            // 3. Create removal notification document
            val notifId = "${myCode}_${formattedTargetCode}"
            val notifJson = JSONObject().apply {
                put("notification_id", notifId)
                put("remover_id", myCode)
                put("remover_name", myName)
                put("target_user_id", formattedTargetCode)
                put("created_at", System.currentTimeMillis())
            }
            SupabaseApi.upsert("removal_notifications", notifJson, onConflict = "notification_id")

            Log.d(tag, "Contact $formattedTargetCode removed by $myCode")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error removing contact: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Dismisses/deletes a removal notification once viewed.
     */
    suspend fun dismissRemovalNotification(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseApi.delete("removal_notifications", "notification_id=eq.$notificationId")
            Log.d(tag, "Removal notification dismissed: $notificationId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error dismissing removal notification: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getContacts(): List<String> = withContext(Dispatchers.IO) {
        val myCode = getUserCodeSync() ?: return@withContext emptyList()
        try {
            val rows = SupabaseApi.select("users", "user_id=eq.$myCode")
            if (rows.length() > 0) {
                val contactsArr = rows.getJSONObject(0).optJSONArray("contacts") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until contactsArr.length()) {
                    list.add(contactsArr.getString(i))
                }
                list
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
