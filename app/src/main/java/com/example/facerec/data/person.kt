package com.example.facerec.data

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val photoPath: String,
    val embeddingJson: String
)

