package com.example.facerec

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.facerec.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonListActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_person_list)

        db = AppDatabase.getInstance(this)
        textView = findViewById(R.id.tv_persons)

        lifecycleScope.launch {
            val persons = withContext(Dispatchers.IO) {
                db.personDao().getAll()
            }

            if (persons.isEmpty()) {
                textView.text = "No enrolled persons found."
            } else {
                val displayText = buildString {
                    persons.forEachIndexed { index, person ->
                        append("Person #${index + 1}\n")
                        append("Name: ${person.name}\n")
                        append("Photo: ${person.photoPath}\n")
                    }
                }
                textView.text = displayText
            }
        }
    }
}
