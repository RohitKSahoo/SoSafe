package com.rohit.sosafe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rohit.sosafe.data.UserManager

class DashboardViewModelFactory(private val userManager: UserManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(userManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
