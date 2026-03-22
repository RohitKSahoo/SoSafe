package com.rohit.sosafe.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class UserManager(private val context: Context) {

    private val TAG = "UserManager"
    private val USER_CODE_KEY = "user_code"
    private val PREFS_NAME = "sosafe_prefs"
    private val db = Firebase.firestore

    // Helper for permission checks in MainActivity without reflection
    fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Synchronous getter for Service use
    fun getUserCodeSync(): String? {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(USER_CODE_KEY, null)
    }

    // Function to get or generate the user code
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
        val length = Random.nextInt(6, 9)
        val allowedChars = ('0'..'9') + ('A'..'Z') + ('a'..'z')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    private suspend fun storeUserCodeInFirestore(userCode: String) {
        val user = hashMapOf(
            "userCode" to userCode,
            "createdAt" to System.currentTimeMillis()
        )
        try {
            db.collection("users").document(userCode).set(user).await()
            Log.d(TAG, "User code '$userCode' stored in Firestore.")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing user code: ${e.message}")
        }
    }

    // --- CONTACT SYSTEM ---

    suspend fun addContact(contactCode: String): Result<Unit> {
        val myCode = getUserCodeSync() ?: return Result.failure(Exception("User not initialized"))
        
        return try {
            // Verify if contact exists
            val contactDoc = db.collection("users").document(contactCode).get().await()
            if (!contactDoc.exists()) {
                return Result.failure(Exception("Invalid Contact Code"))
            }

            // Add to my contacts collection
            val contactData = hashMapOf(
                "contactCode" to contactCode,
                "addedAt" to System.currentTimeMillis()
            )
            
            db.collection("users").document(myCode)
                .collection("contacts").document(contactCode)
                .set(contactData).await()
                
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getContacts(): List<String> {
        val myCode = getUserCodeSync() ?: return emptyList()
        return try {
            val snapshot = db.collection("users").document(myCode)
                .collection("contacts").get().await()
            snapshot.documents.mapNotNull { it.getString("contactCode") }
        } catch (e: Exception) {
            emptyList()
        }
    }
}