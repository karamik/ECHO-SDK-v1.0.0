package com.echo.sdk.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "token_balances")
data class TokenBalanceEntity(
    @PrimaryKey
    val pubKeyHex: String,
    val balance: Long
)
