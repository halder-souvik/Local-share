package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedFolderDao {
    @Query("SELECT * FROM shared_folders ORDER BY createdAt DESC")
    fun getAllSharedFolders(): Flow<List<SharedFolder>>

    @Query("SELECT * FROM shared_folders WHERE id = :id")
    suspend fun getSharedFolderById(id: Int): SharedFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedFolder(folder: SharedFolder): Long

    @Update
    suspend fun updateSharedFolder(folder: SharedFolder)

    @Delete
    suspend fun deleteSharedFolder(folder: SharedFolder)

    @Query("UPDATE shared_folders SET isActive = 0")
    suspend fun deactivateAll()
}
