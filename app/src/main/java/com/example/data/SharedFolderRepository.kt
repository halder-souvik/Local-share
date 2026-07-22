package com.example.data

import kotlinx.coroutines.flow.Flow

class SharedFolderRepository(private val dao: SharedFolderDao) {
    val allFolders: Flow<List<SharedFolder>> = dao.getAllSharedFolders()

    suspend fun getFolderById(id: Int): SharedFolder? = dao.getSharedFolderById(id)

    suspend fun insert(folder: SharedFolder): Long = dao.insertSharedFolder(folder)

    suspend fun update(folder: SharedFolder) = dao.updateSharedFolder(folder)

    suspend fun delete(folder: SharedFolder) = dao.deleteSharedFolder(folder)

    suspend fun deactivateAll() = dao.deactivateAll()
}
