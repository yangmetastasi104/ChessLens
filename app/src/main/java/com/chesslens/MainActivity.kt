package com.chesslens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chesslens.chess.ChessPosition
import com.chesslens.overlay.OverlayService
import com.chesslens.services.ScreenCaptureService

class MainActivity : ComponentActivity() {
    private var requestedFen: String = ChessPosition.fromFen(START_FEN).toFen()!!
    private var requestedWhiteAtBottom: Boolean = true
    private var captureRequestStillTraining = false
    private var pendingOverlayPermission = false
    private var statusText by mutableStateOf("Capture is off")
    private var overlayGranted by mutableStateOf(false)

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val stillAuthorized = captureRequestStillTraining
        captureRequestStillTraining = false
        if (!stillAuthorized) {
            statusText = "Training was cancelled; nothing was captured."
            return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK || result.data == null) {
            statusText = "Screen-capture permission denied. No frames were captured."
            return@registerForActivityResult
        }
        if (!hasOverlayPermission()) {
            statusText = "Overlay permission is required for on-board suggestions."
            return@registerForActivityResult
        }
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_CLEAR
            putExtra(OverlayService.EXTRA_TEXT, "Starting screen capture…")
            putExtra(OverlayService.EXTRA_STATUS, "Keep the chessboard visible and stable.")
        })
        ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            putExtra(ScreenCaptureService.EXTRA_INITIAL_FEN, requestedFen)
            putExtra(ScreenCaptureService.EXTRA_WHITE_AT_BOTTOM, requestedWhiteAtBottom)
            putExtra(ScreenCaptureService.EXTRA_TRAINING_CONFIRMED, true)
            putExtra(ScreenCaptureService.EXTRA_POSITION_CONFIRMED, true)
        })
        statusText = "Capture active. Keep the chessboard visible and stable."
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        launchCapture()
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingOverlayPermission = true
            statusText = "Grant the overlay permission, then return to ChessLens."
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun launchCapture() {
        if (!hasOverlayPermission()) {
            openOverlayPermission()
            return
        }
        statusText = "Waiting for screen-capture permission…"
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun requestNotificationsThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayGranted = hasOverlayPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF4ADE80))) {
                var training by remember { mutableStateOf(false) }
                var visiblePositionConfirmed by remember { mutableStateOf(false) }
                var fen by remember { mutableStateOf(START_FEN) }
                var whiteAtBottom by remember { mutableStateOf(true) }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("ChessLens", style = MaterialTheme.typography.headlineMedium)
                        Text("Offline-first chess training assistant")
                        Text("Suggestions are disabled until you explicitly select a permitted training activity.")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = training, onClick = { training = true }, label = { Text("Training") })
                            FilterChip(selected = !training, onClick = {
                                training = false
                                captureRequestStillTraining = false
                                pendingOverlayPermission = false
                                visiblePositionConfirmed = false
                                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                                statusText = "Capture is off"
                            }, label = { Text("Not training") })
                        }

                        Text("Initial position (FEN)", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = fen,
                            onValueChange = {
                                fen = it
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            enabled = training,
                            supportingText = { Text("Use a trusted position so capture history and special-move state are not guessed.") }
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = visiblePositionConfirmed,
                                onCheckedChange = { visiblePositionConfirmed = it },
                                enabled = training
                            )
                            Text("I confirm the visible board currently matches this FEN.")
                        }

                        Text("I tuoi pezzi sono sempre in basso. Seleziona il tuo colore:")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = whiteAtBottom, onClick = {
                                whiteAtBottom = true
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
                            }, label = { Text("Io: Bianco") })
                            FilterChip(selected = !whiteAtBottom, onClick = {
                                whiteAtBottom = false
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
                            }, label = { Text("Io: Nero") })
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (overlayGranted) "Overlay permission: granted"
                                else "Overlay permission: required"
                            )
                            if (!overlayGranted) {
                                TextButton(onClick = { openOverlayPermission() }) { Text("Grant") }
                            }
                        }

                        Button(
                            enabled = training && visiblePositionConfirmed,
                            onClick = {
                                val validFen = runCatching { ChessPosition.fromFen(fen).toFen() }.getOrNull()
                                if (validFen == null) {
                                    statusText = "Enter a complete, valid six-field FEN."
                                    return@Button
                                }
                                requestedFen = validFen
                                requestedWhiteAtBottom = whiteAtBottom
                                captureRequestStillTraining = true
                                requestNotificationsThenCapture()
                            }
                        ) { Text("Start training capture") }

                        Text(statusText)
                        Text("The floating panel can be dragged. Drag its bottom-right corner to resize it.")
                        Text("Screen capture requires your consent each session. Frames remain on device.")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = hasOverlayPermission()
        if (pendingOverlayPermission) {
            pendingOverlayPermission = false
            if (overlayGranted) {
                statusText = "Overlay permission granted. Requesting screen capture…"
                requestNotificationsThenCapture()
            } else {
                statusText = "Overlay permission is required for on-board suggestions."
            }
        }
    }

    companion object {
        private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
