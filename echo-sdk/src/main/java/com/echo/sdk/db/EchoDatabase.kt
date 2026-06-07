// echo-sdk/src/main/java/com/echo/sdk/db/EchoDatabase.kt
package com.echo.sdk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.echo.sdk.db.dao.DagMessageDao
import com.echo.sdk.db.dao.ProcessedReceiptDao
import com.echo.sdk.db.dao.TokenBalanceDao
import com.echo.sdk.db.entity.DagMessageEntity
import com.echo.sdk.db.entity.ProcessedReceiptEntity
import com.echo.sdk.db.entity.TokenBalanceEntity

@Database(
    entities = [
        DagMessageEntity::class,
        TokenBalanceEntity::class,
        ProcessedReceiptEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun dagMessageDao(): DagMessageDao
    abstract fun tokenBalanceDao(): TokenBalanceDao
    abstract fun processedReceiptDao(): ProcessedReceiptDao

    companion object {
        @Volatile
        private var INSTANCE: EchoDatabase? = null

        fun getInstance(context: Context): EchoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EchoDatabase::class.java,
                    "echo_db"
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
        }
    }
}
