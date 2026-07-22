package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_folders")
data class SharedFolder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val uriString: String,
    val port: Int = 8080,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val bytesTransferred: Long = 0L
)
