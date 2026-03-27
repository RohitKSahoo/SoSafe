package com.rohit.sosafe.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.rohit.sosafe.data.contracts.SoSafeContract
import com.rohit.sosafe.data.contracts.User
import kotlinx.coroutines.tasks.await

class UserManager(private val context: Context) {

    private val tag = "UserManager"
    private val userCodeKey = "user_code"
    private val prefsName = "sosafe_prefs"
    private val db = Firebase.firestore

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
            storeUserCodeInFirestore(userCode)
        } else {
            // Even if user exists, refresh the FCM token in Firestore
            updateFcmToken(userCode)
        }
        return userCode
    }

    private fun generateUniqueUserCode(): String {
        val length = 8
        val allowedChars = ('0'..'9') + ('A'..'Z')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    private suspend fun storeUserCodeInFirestore(userCode: String) {
        val fcmToken = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            ""
        }

        val user = User(
            userId = userCode,
            contacts = emptyList(),
            fcmToken = fcmToken,
            createdAt = System.currentTimeMillis()
        )
        try {
            db.collection(SoSafeContract.Collections.USERS)
                .document(userCode)
                .set(user)
                .await()
            Log.d(tag, "User code '$userCode' stored in Firestore.")
        } catch (e: Exception) {
            Log.e(tag, "Error storing user code: ${e.message}")
        }
    }

    suspend fun updateFcmToken(userCode: String) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            db.collection(SoSafeContract.Collections.USERS)
                .document(userCode)
                .update(SoSafeContract.Fields.FCM_TOKEN, token)
                .await()
            Log.d(tag, "FCM Token updated successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to update FCM token: ${e.message}")
        }
    }

    /**
     * SYMMETRIC LINKING: Ensures both users are added to each other's contact list atomically.
     */
    suspend fun addContact(inputCode: String, isGuardianMode: Boolean): Result<Unit> {
        val myCode = getUserCode() // Ensure user exists in Firestore
        val contactCode = inputCode.replace("-", "").trim().uppercase()

        if (myCode == contactCode) return Result.failure(Exception("Cannot link to self"))

        return try {
            val contactDoc = db.collection(SoSafeContract.Collections.USERS)
                .document(contactCode)
                .get()
                .await()
                
            if (!contactDoc.exists()) {
                return Result.failure(Exception("Invalid Contact Code"))
            }

            val batch = db.batch()
            
            val myRef = db.collection(SoSafeContract.Collections.USERS).document(myCode)
            val contactRef = db.collection(SoSafeContract.Collections.USERS).document(contactCode)

            // Add each other to contacts
            batch.update(myRef, SoSafeContract.Fields.CONTACTS, FieldValue.arrayUnion(contactCode))
            batch.update(contactRef, SoSafeContract.Fields.CONTACTS, FieldValue.arrayUnion(myCode))
            
            batch.commit().await()
            Log.d(tag, "Symmetric link established between $myCode and $contactCode")
                
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Error adding contact: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getContacts(): List<String> {
        val myCode = getUserCodeSync() ?: return emptyList()
        return try {
            val doc = db.collection(SoSafeContract.Collections.USERS)
                .document(myCode)
                .get()
                .await()
            @Suppress("UNCHECKED_CAST")
            (doc.get(SoSafeContract.Fields.CONTACTS) as? List<String>) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
