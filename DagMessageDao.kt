// echo-sdk/src/main/java/com/echo/sdk/db/dao/DagMessageDao.kt
package com.echo.sdk.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.echo.sdk.db.entity.DagMessageEntity

@Dao
interface DagMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: DagMessageEntity)

    @Query("SELECT DISTINCT authorPubkey FROM dag_messages")
    suspend fun getDistinctAuthors(): List<String>

    @Query("SELECT MAX(sequenceNumber) FROM dag_messages WHERE authorPubkey = :author")
    suspend fun getLastSeq(author: String): Long?

    @Query("SELECT messageId FROM dag_messages WHERE authorPubkey = :author ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun getHeadHash(author: String): String?

    @Query("SELECT * FROM dag_messages WHERE authorPubkey = :author AND sequenceNumber > :fromSeq ORDER BY sequenceNumber ASC")
    suspend fun getMessagesSince(author: String, fromSeq: Long): List<DagMessageEntity>

    @Query("SELECT * FROM dag_messages WHERE authorPubkey = :author ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun getLastMessageByAuthor(author: String): DagMessageEntity?

    @Query("DELETE FROM dag_messages WHERE timestamp < :before")
    suspend fun pruneOldMessages(before: Long)
}
