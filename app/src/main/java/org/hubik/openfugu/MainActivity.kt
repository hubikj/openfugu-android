package org.hubik.openfugu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.session.Session
import org.hubik.openfugu.ui.MockDeviceOverlay
import org.hubik.openfugu.ui.theme.OpenFuguTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var efuguViewModel: EFuguViewModel

    // Session file handed to us via ACTION_VIEW/ACTION_SEND. Compose state so
    // EFuguApp picks it up whether it arrives in onCreate or onNewIntent.
    private var importIntent by mutableStateOf<Intent?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            efuguViewModel.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importIntent = intent
        setContent {
            OpenFuguTheme {
                efuguViewModel = viewModel()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    efuguViewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
                }
                // Resolve the Android intent to a plain suspend loader here so
                // EFuguApp stays free of Intent/Uri types.
                val importLoader: (suspend () -> Session?)? = remember(importIntent) {
                    importIntent?.let(::sessionUriOf)?.let { uri ->
                        suspend { efuguViewModel.importSession(uri) }
                    }
                }
                Box {
                    EFuguApp(
                        viewModel = efuguViewModel,
                        onRequestPermissionsAndScan = { requestPermissionsAndScan() },
                        importSession = importLoader,
                        onImportSessionHandled = { importIntent = null },
                        onSaveLogs = ::saveLogsToFile
                    )
                    // Slider controls for simulated devices, drawn over every
                    // screen (games included) while any mock is connected.
                    MockDeviceOverlay(viewModel = efuguViewModel)
                    // Messages float over every screen, games included — the
                    // same reach the old toasts had.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importIntent = intent
    }

    private fun sessionUriOf(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    private fun saveLogsToFile(messages: List<String>): String = try {
        val file = File(getExternalFilesDir(null), "openfugu_log.txt")
        file.writeText(messages.joinToString("\n"))
        "Saved: ${file.absolutePath}"
    } catch (e: Exception) {
        "Could not save logs"
    }

    private fun requestPermissionsAndScan() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            efuguViewModel.startScan()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
