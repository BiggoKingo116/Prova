package it.roma.metroa.dev

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import it.roma.metroa.MainActivity
import it.roma.metroa.ui.MetroTheme

/** MetroA Dev: schermata iniziale dell'app sviluppatore. L'app normale si apre dal pulsante "MetroA". */
class DevActivity : ComponentActivity() {
    private val vm: DevViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MetroTheme {
                DevScreen(vm, openMetroA = { startActivity(Intent(this, MainActivity::class.java)) })
            }
        }
    }
}
