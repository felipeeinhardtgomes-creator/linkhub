package br.com.ppci.levantamento

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import br.com.ppci.levantamento.ui.PpciTheme
import br.com.ppci.levantamento.ui.home.HomeScreen
import br.com.ppci.levantamento.ui.viewer.ViewerScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PpciTheme {
                var surveyId by rememberSaveable { mutableStateOf<Long?>(null) }
                val id = surveyId
                if (id == null) HomeScreen(onStarted = { surveyId = it }) else ViewerScreen(id, onBack = { surveyId = null })
            }
        }
    }
}
