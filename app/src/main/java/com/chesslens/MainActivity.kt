package com.chesslens

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.chesslens.services.ScreenCaptureService
import com.chesslens.chess.ChessPosition

class MainActivity : ComponentActivity() {
    private var requestedFen: String = ChessPosition.fromFen(START_FEN).toFen()!!
    private var requestedWhiteAtBottom: Boolean = true
    private var captureRequestStillTraining = false
    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val stillAuthorized = captureRequestStillTraining
        captureRequestStillTraining = false
        if (stillAuthorized && result.resultCode == RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_INITIAL_FEN, requestedFen)
                putExtra(ScreenCaptureService.EXTRA_WHITE_AT_BOTTOM, requestedWhiteAtBottom)
                putExtra(ScreenCaptureService.EXTRA_TRAINING_CONFIRMED, true)
                putExtra(ScreenCaptureService.EXTRA_POSITION_CONFIRMED, true)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF4ADE80))) {
                var training by remember { mutableStateOf(false) }
                var visiblePositionConfirmed by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("Capture is off") }
                var fen by remember { mutableStateOf(START_FEN) }
                var whiteAtBottom by remember { mutableStateOf(true) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("ChessLens ♛", style = MaterialTheme.typography.headlineMedium)
                        Text("Offline-first chess training assistant")
                        Text("Suggestions are disabled until you explicitly select a permitted training activity.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = training, onClick = { training = true }, label = { Text("Training") })
                            FilterChip(selected = !training, onClick = {
                                training = false
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
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
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = visiblePositionConfirmed,
                                onCheckedChange = { visiblePositionConfirmed = it },
                                enabled = training
                            )
                            Text("I confirm the visible board currently matches this FEN.")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = whiteAtBottom, onClick = {
                                whiteAtBottom = true
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
                            }, label = { Text("White at bottom") })
                            FilterChip(selected = !whiteAtBottom, onClick = {
                                whiteAtBottom = false
                                captureRequestStillTraining = false
                                visiblePositionConfirmed = false
                            }, label = { Text("Black at bottom") })
                        }
                        Button(enabled = training && visiblePositionConfirmed, onClick = {
                            val validFen = runCatching { ChessPosition.fromFen(fen).toFen() }.getOrNull()
                            if (validFen == null) {
                                status = "Enter a complete, valid six-field FEN."
                                return@Button
                            }
                            requestedFen = validFen
                            requestedWhiteAtBottom = whiteAtBottom
                            captureRequestStillTraining = training && visiblePositionConfirmed
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            capturePermission.launch(manager.createScreenCaptureIntent())
                            status = "Waiting for screen-capture permission"
                        }) { Text("Start training capture") }
                        Text(status)
                        Text("Screen capture requires your consent each session. Frames remain on device.")
                    }
                }
            }
        }
    }

    companion object {
        private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
