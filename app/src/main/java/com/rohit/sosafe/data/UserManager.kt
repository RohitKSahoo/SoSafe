package com.rohit.sosafe.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rohit.sosafe.data.contracts.SoSafeContract
import com.rohit.sosafe.data.contracts.User
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class UserManager(private val context: Context) {

    private val TAG = "UserManager"
    private val USER_CODE_KEY = "user_code"
    private val PREFS_NAME = "sosafe_prefs"
    private val db = Firebase.firestore

    fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun getUserCodeSync(): String? {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(USER_CODE_KEY, null)
    }

    suspend fun getUserCode(): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var userCode = sharedPrefs.getString(USER_CODE_KEY, null)

        if (userCode == null) {
            userCode = generateUniqueUserCode()
            sharedPrefs.edit().putString(USER_CODE_KEY, userCode).apply()
            storeUserCodeInFirestore(userCode)
        }
        return userCode
    }

    private fun generateUniqueUserCode(): String {
        val length = 8
        val allowedChars = ('0'..'9') + ('A'..'Z') + ('a'..'z')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    private suspend fun storeUserCodeInFirestore(userCode: String) {
        val user = User(
            userCode = userCode,
            contacts = emptyList(),
            createdAt = System.currentTimeMillis()
        )
        try {
            db.collection(SoSafeContract.Collections.USERS)
                .document(userCode)
                .set(user)
                .await()
            Log.d(TAG, "User code '$userCode' stored in Firestore.")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing user code: ${e.message}")
        }
    }

    suspend fun addContact(inputCode: String): Result<Unit> {
        val myCode = getUserCodeSync() ?: return Result.failure(Exception("User not initialized"))
        val contactCode = inputCode.replace("-", "").trim()

        return try {
            val contactDoc = db.collection(SoSafeContract.Collections.USERS)
                .document(contactCode)
                .get()
                .await()
            if (!contactDoc.exists()) {
                return Result.failure(Exception("Invalid Contact Code"))
            }

            db.collection(SoSafeContract.Collections.USERS)
                .document(myCode)
                .update(SoSafeContract.Fields.CONTACTS, FieldValue.arrayUnion(contactCode))
                .await()
                
            Result.success(Unit)
        } catch (e: Exception) {
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