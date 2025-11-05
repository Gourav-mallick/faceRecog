package com.example.facerec.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query


@Dao
interface PersonDao {

    @Insert
    suspend fun insert(person: Person): Long

    @Query("SELECT * FROM persons")
    suspend fun getAll(): List<Person>

    @Query("SELECT * FROM persons WHERE studentId LIKE :query OR studentName LIKE :query")
    suspend fun searchPerson(query: String): List<Person>

    @Query("UPDATE persons SET photoPath = :photoPath, embeddingJson = :embeddingJson WHERE studentId = :studentId")
    suspend fun updateEnrollment(studentId: String, photoPath: String, embeddingJson: String)
}

