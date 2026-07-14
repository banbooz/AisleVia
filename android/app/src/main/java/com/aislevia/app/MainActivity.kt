package com.aislevia.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aislevia.app.ui.AisleViaApp

class MainActivity : ComponentActivity() {
    private var cameraGranted by mutableStateOf(false)
    private var previousCrash by mutableStateOf<String?>(null)

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        previousCrash = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            .getString(CRASH_REPORT, null)
        installCrashRecorder()

        setContent {
            when {
                previousCrash != null -> RecoveryScreen(
                    report = previousCrash.orEmpty(),
                    onCopy = { copyCrashReport(previousCrash.orEmpty()) },
                    onContinue = {
                        getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                            .edit()
                            .remove(CRASH_REPORT)
                            .apply()
                        previousCrash = null
                    }
                )

                cameraGranted -> AisleViaApp()

                else -> CameraPermissionScreen(
                    onRequestPermission = { cameraPermission.launch(Manifest.permission.CAMERA) }
                )
            }
        }

        if (!cameraGranted && previousCrash == null) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun installCrashRecorder() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = buildString {
                append(error::class.java.name)
                append(": ")
                appendLine(error.message.orEmpty())
                append(error.stackTraceToString())
            }
            getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                .edit()
                .putString(CRASH_REPORT, report.take(24_000))
                .commit()
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun copyCrashReport(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AisleVia crash report", report))
    }

    companion object {
        private const val CRASH_PREFS = "aislevia-crashes"
        private const val CRASH_REPORT = "latest-crash"
    }
}

private val entryColours = darkColorScheme(
    primary = Color(0xFF2CF58A),
    background = Color(0xFF06131F),
    surface = Color(0xFF0D2132),
    onPrimary = Color(0xFF00180B),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
private fun CameraPermissionScreen(onRequestPermission: () -> Unit) {
    EntryScreen(
        title = "Camera permission needed",
        message = "AisleVia uses the camera to recognise the saved room automatically and place the route arrows on the floor.",
        primaryText = "Allow camera",
        onPrimary = onRequestPermission
    )
}

@Composable
private fun RecoveryScreen(
    report: String,
    onCopy: () -> Unit,
    onContinue: () -> Unit
) {
    EntryScreen(
        title = "AisleVia recovered from a crash",
        message = report.lineSequence().take(5).joinToString("\n"),
        primaryText = "Copy crash report",
        onPrimary = onCopy,
        secondaryText = "Continue to app",
        onSecondary = onContinue
    )
}

@Composable
private fun EntryScreen(
    title: String,
    message: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    MaterialTheme(colorScheme = entryColours) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("AISLEVIA", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFC8D8E4))
                Spacer(Modifier.height(22.dp))
                Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                    Text(primaryText)
                }
                if (secondaryText != null && onSecondary != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                        Text(secondaryText)
                    }
                }
            }
        }
    }
}
