package xyz.a202132.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import xyz.a202132.app.ui.components.AppScreenScaffold

@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onQrCodeScanned: (String, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scanLocked by remember { mutableStateOf(false) }
    var cameraHidden by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    AppScreenScaffold(
        title = "\u626b\u63cf\u4e8c\u7ef4\u7801",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        if (hasCameraPermission && !cameraHidden) {
            QrCameraPreview(
                modifier = Modifier.fillMaxSize(),
                onQrCodeScanned = { value ->
                    if (!scanLocked) {
                        scanLocked = true
                        cameraHidden = true
                        onQrCodeScanned(value) { success ->
                            if (!success) {
                                scanLocked = false
                                cameraHidden = false
                            }
                        }
                    }
                }
            )
        } else if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "\u9700\u8981\u76f8\u673a\u6743\u9650\u624d\u80fd\u626b\u63cf\u4e8c\u7ef4\u7801",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("\u6388\u6743\u76f8\u673a")
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
private fun QrCameraPreview(
    modifier: Modifier = Modifier,
    onQrCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.matchParentSize()
        )
        ScannerFrameOverlay(
            hasFlashUnit = hasFlashUnit,
            torchEnabled = torchEnabled,
            onToggleTorch = {
                val next = !torchEnabled
                camera?.cameraControl?.enableTorch(next)
                torchEnabled = next
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var boundCamera: Camera? = null
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        val listener = Runnable {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes.firstNotNullOfOrNull { it.rawValue }
                        if (!value.isNullOrBlank()) {
                            onQrCodeScanned(value)
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            cameraProvider.unbindAll()
            boundCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            camera = boundCamera
            hasFlashUnit = boundCamera?.cameraInfo?.hasFlashUnit() == true
            torchEnabled = false
        }

        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            runCatching { cameraProviderFuture.get().unbindAll() }
            scanner.close()
            analyzerExecutor.shutdown()
        }
    }
}

@Composable
private fun ScannerFrameOverlay(
    hasFlashUnit: Boolean,
    torchEnabled: Boolean,
    onToggleTorch: () -> Unit
) {
    val frameColor = MaterialTheme.colorScheme.primary
    val scanTransition = rememberInfiniteTransition(label = "qr_scan_line")
    val scanProgress by scanTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "qr_scan_line_progress"
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val frameSize = minOf(maxWidth - 40.dp, maxHeight - 128.dp, 340.dp).coerceAtLeast(220.dp)

        Box(modifier = Modifier.size(frameSize)) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val thinStroke = 1.5.dp.toPx()
                val cornerStroke = 7.dp.toPx()
                val cornerLength = 42.dp.toPx()

                drawRect(
                    color = frameColor.copy(alpha = 0.38f),
                    style = Stroke(width = thinStroke)
                )

                drawLine(frameColor, Offset.Zero, Offset(cornerLength, 0f), cornerStroke)
                drawLine(frameColor, Offset.Zero, Offset(0f, cornerLength), cornerStroke)

                drawLine(frameColor, Offset(size.width, 0f), Offset(size.width - cornerLength, 0f), cornerStroke)
                drawLine(frameColor, Offset(size.width, 0f), Offset(size.width, cornerLength), cornerStroke)

                drawLine(frameColor, Offset(0f, size.height), Offset(cornerLength, size.height), cornerStroke)
                drawLine(frameColor, Offset(0f, size.height), Offset(0f, size.height - cornerLength), cornerStroke)

                drawLine(frameColor, Offset(size.width, size.height), Offset(size.width - cornerLength, size.height), cornerStroke)
                drawLine(frameColor, Offset(size.width, size.height), Offset(size.width, size.height - cornerLength), cornerStroke)

                val glowHeight = 8.dp.toPx()
                val glowY = size.height * scanProgress
                drawOval(
                    color = frameColor.copy(alpha = 0.40f),
                    topLeft = Offset(size.width * 0.08f, glowY - glowHeight / 2f),
                    size = Size(size.width * 0.84f, glowHeight)
                )
                drawOval(
                    color = Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(size.width * 0.16f, glowY - 1.dp.toPx()),
                    size = Size(size.width * 0.68f, 2.dp.toPx())
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-40).dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onToggleTorch,
                    enabled = hasFlashUnit,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = "\u624b\u7535\u7b52",
                        tint = when {
                            !hasFlashUnit -> Color.White.copy(alpha = 0.32f)
                            torchEnabled -> frameColor
                            else -> Color.White
                        }
                    )
                }
            }
        }
    }
}
