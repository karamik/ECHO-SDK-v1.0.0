// echo-sdk/src/main/java/com/echo/sdk/db/dao/TokenBalanceDao.kt
package com.echo.sdk.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.echo.sdk.db.entity.TokenBalanceEntity

@Dao
interface TokenBalanceDao {
    @Query("SELECT * FROM token_balances")
    suspend fun getAllBalances(): List<TokenBalanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateBalance(entity: TokenBalanceEntity)

    @Query("UPDATE token_balances SET balance = :balance WHERE pubKeyHex = :pubKeyHex")
    suspend fun setBalance(pubKeyHex: String, balance: Long)
}
