package com.example

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.SharedFolder
import com.example.data.SharedFolderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SharedFolderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SharedFolderRepository
    val allFolders: StateFlow<List<SharedFolder>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = SharedFolderRepository(database.sharedFolderDao())
        allFolders = repository.allFolders.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addFolder(name: String, uriString: String, port: Int) {
        viewModelScope.launch {
            repository.insert(
                SharedFolder(
                    name = name,
                    uriString = uriString,
                    port = port
                )
            )
        }
    }

    fun deleteFolder(folder: SharedFolder) {
        viewModelScope.launch {
            if (folder.isActive) {
                stopShare()
            }
            repository.delete(folder)
        }
    }

    fun startShare(folder: SharedFolder) {
        val context = getApplication<Application>()
        val intent = Intent(context, ShareService::class.java).apply {
            action = ShareService.ACTION_START
            putExtra(ShareService.EXTRA_ID, folder.id)
            putExtra(ShareService.EXTRA_NAME, folder.name)
            putExtra(ShareService.EXTRA_URI, folder.uriString)
            putExtra(ShareService.EXTRA_PORT, folder.port)
        }
        context.startService(intent)
    }

    fun stopShare() {
        val context = getApplication<Application>()
        val intent = Intent(context, ShareService::class.java).apply {
            action = ShareService.ACTION_STOP
        }
        context.startService(intent)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SharedFolderViewModel::class.java)) {
                return SharedFolderViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
