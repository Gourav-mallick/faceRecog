package com.example.facerec.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey val studentId: String,   // manually assigned, not auto
    val studentName: String,
    val photoPath: String?,
    val embeddingJson: String?,
    val present: Boolean = false
)


