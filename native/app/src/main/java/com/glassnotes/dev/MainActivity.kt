package com.glassnotes.dev

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Точка входа dev-сборки нативного пути (этап 2 — экран записи).
 *
 * Диагностика без пальца и без микрофона: `--ez wave_test true` прогоняет алгоритм полос
 * синтетическим сигналом и пишет результат в лог. Так проверялся и JS-вариант в 2.73.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra("wave_test", false) == true) {
            Log.i("GlasNotesDev", "=== самопроверка полос запущена из интента ===")
            WaveSelfTest.run()
            Log.i("GlasNotesDev", "=== самопроверка полос закончена ===")
        }

        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF8AB4F8),
                        surface = Color(0xFF17181C),
                        background = Color(0xFF101114),
                    ),
            ) {
                RecordingScreen(autoStart = intent?.getBooleanExtra("record", false) == true)
            }
        }
    }
}
