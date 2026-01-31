package com.void.block.contacts.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.void.block.contacts.domain.ContactQRData
import com.void.block.contacts.ui.viewmodels.AddContactUiState
import com.void.block.contacts.ui.viewmodels.AddContactViewModel
import com.void.slate.design.theme.TerminalStandard
import org.koin.androidx.compose.koinViewModel
import java.util.concurrent.Executors

/**
 * Screen for scanning QR codes to add contacts.
 *
 * Features:
 * - Camera preview with QR scanning
 * - Permission handling
 * - Automatic contact addition on successful scan
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQRScreen(
    onNavigateBack: () -> Unit,
    onContactAdded: (String) -> Unit,
    viewModel: AddContactViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Request permission on first launch
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Handle UI state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AddContactUiState.Success -> {
                onContactAdded(state.contactId)
                viewModel.resetState()
            }
            is AddContactUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetState()
            }
            AddContactUiState.Input -> {
                // Nothing to do
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = TerminalStandard.header("SCAN QR CODE"),
                        style = TerminalStandard.Header,
                        color = TerminalStandard.Text
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(
                            text = TerminalStandard.bracketLabel("<"),
                            style = TerminalStandard.Body,
                            color = TerminalStandard.Text
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalStandard.Background,
                    titleContentColor = TerminalStandard.Text
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (hasCameraPermission) {
            // CameraX-based camera view with background QR scanning
            val lifecycleOwner = LocalLifecycleOwner.current
            var hasScanned by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()

                                // Preview use case
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // Image analysis use case for QR scanning
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                // QR code analyzer running on background thread
                                val qrAnalyzer = QRCodeAnalyzer { qrText ->
                                    if (!hasScanned) {
                                        android.util.Log.d("VOID_QR", "📷 [CAMERAX] QR code detected!")
                                        android.util.Log.d("VOID_QR", "  Raw text length: ${qrText.length} chars")
                                        android.util.Log.d("VOID_QR", "  First 100 chars: ${qrText.take(100)}")

                                        try {
                                            val qrData = ContactQRData.fromJson(qrText)
                                            if (qrData != null) {
                                                android.util.Log.d("VOID_QR", "✅ [CAMERAX] Valid QR code parsed successfully")
                                                hasScanned = true
                                                viewModel.addContactFromQR(qrData)
                                            } else {
                                                android.util.Log.e("VOID_QR", "❌ [CAMERAX] QR code parsed but returned null")
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("VOID_QR", "❌ [CAMERAX] Failed to parse QR code JSON", e)
                                        }
                                    }
                                }

                                imageAnalysis.setAnalyzer(
                                    Executors.newSingleThreadExecutor(),  // Background thread for analysis
                                    qrAnalyzer
                                )

                                // Bind to lifecycle
                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )

                                android.util.Log.d("VOID_QR", "📷 [CAMERAX] Camera initialized successfully")
                            } catch (e: Exception) {
                                android.util.Log.e("VOID_QR", "❌ [CAMERAX] Camera initialization failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Instruction overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Point camera at QR code",
                        style = TerminalStandard.Body,
                        color = TerminalStandard.Text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        } else {
            // Permission denied state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = "CAMERA PERMISSION REQUIRED",
                    style = TerminalStandard.Header,
                    color = TerminalStandard.Text,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Camera access is needed to scan QR codes",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = TerminalStandard.Text,
                        contentColor = TerminalStandard.Background
                    )
                ) {
                    Text(
                        text = TerminalStandard.bracketLabel("GRANT PERMISSION"),
                        style = TerminalStandard.Button
                    )
                }
            }
        }
    }
}

/**
 * QR code analyzer for CameraX.
 * Runs on a background thread to avoid blocking the camera preview.
 */
private class QRCodeAnalyzer(
    private val onQRCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        setHints(hints)
    }

    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val source = PlanarYUVLuminanceSource(
            data,
            imageProxy.width,
            imageProxy.height,
            0,
            0,
            imageProxy.width,
            imageProxy.height,
            false
        )

        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decode(binaryBitmap)
            onQRCodeDetected(result.text)
        } catch (e: Exception) {
            // No QR code found in this frame, continue scanning
        } finally {
            imageProxy.close()
        }
    }
}
