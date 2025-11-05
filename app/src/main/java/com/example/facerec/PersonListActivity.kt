package com.example.facerec

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.facerec.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listView = ListView(this)
        setContentView(listView)

        db = AppDatabase.getInstance(this)

        lifecycleScope.launch {
            val persons = withContext(Dispatchers.IO) { db.personDao().getAll() }
            val display = persons.map { p ->
                val emb = if (p.embeddingJson.isNullOrEmpty()) "no-emb" else "emb"
                val photo = if (p.photoPath.isNullOrEmpty()) "no-photo" else "photo"
                "ID:${p.studentId}  ${p.studentName}  [$photo/$emb]"
            }
            listView.adapter = ArrayAdapter(this@PersonListActivity, android.R.layout.simple_list_item_1, display)
        }
    }
}
