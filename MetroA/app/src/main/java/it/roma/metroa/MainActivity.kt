package it.roma.metroa

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.roma.metroa.ui.MetroScreen
import it.roma.metroa.ui.MetroTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MetroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Aggiorna le posizioni solo quando l'app è visibile: niente batteria sprecata in background
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() }
        }
        // Schermo sempre acceso, se scelto nelle impostazioni (utile in banchina)
        lifecycleScope.launch {
            vm.settings.collect { s ->
                if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            MetroTheme(settings.theme) { MetroScreen(vm) }
        }
    }
}
