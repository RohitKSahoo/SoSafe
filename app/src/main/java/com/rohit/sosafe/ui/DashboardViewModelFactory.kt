package com.rohit.sosafe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rohit.sosafe.data.AppModeManager
import com.rohit.sosafe.data.StreamingModeManager
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.RecordingManager

class DashboardViewModelFactory(
    private val userManager: UserManager,
    private val appModeManager: AppModeManager,
    private val streamingModeManager: StreamingModeManager,
    private val recordingManager: RecordingManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(userManager, appModeManager, streamingModeManager, recordingManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
