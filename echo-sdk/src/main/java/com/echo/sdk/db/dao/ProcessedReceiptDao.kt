// echo-sdk/src/main/java/com/echo/sdk/db/dao/ProcessedReceiptDao.kt
package com.echo.sdk.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.echo.sdk.db.entity.ProcessedReceiptEntity

@Dao
interface ProcessedReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProcessedReceiptEntity)
}
