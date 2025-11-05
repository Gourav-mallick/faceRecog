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
}