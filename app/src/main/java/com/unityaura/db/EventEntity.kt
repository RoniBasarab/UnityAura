package com.unityaura.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey
    val uuid: String,
    val eventName: String,
    val eventTimestamp: Long,
    val eventMetadata: String
)
