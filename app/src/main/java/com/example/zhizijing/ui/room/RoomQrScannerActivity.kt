package com.example.zhizijing.ui.room

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.zhizijing.databinding.ActivityRoomQrScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RoomQrScannerActivity : ComponentActivity() {
    private lateinit var binding: ActivityRoomQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private val barcodeScanner = BarcodeScanning.getClient()
    @Volatile
    private var isProcessingFrame = false
    @Volatile
    private var hasResult = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.scanStatusText.text = "相机权限被拒绝，无法扫码。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.restartScanButton.setOnClickListener {
            hasResult = false
            requestOrStartCamera()
        }
        binding.backButton.setOnClickListener { finish() }
        requestOrStartCamera()
    }

    private fun requestOrStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            binding.scanStatusText.text = "需要相机权限才能扫描房间二维码。"
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.scanStatusText.text = "正在启动扫码预览..."
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { preview -> preview.setSurfaceProvider(binding.previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (hasResult || isProcessingFrame) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        isProcessingFrame = true
                        runCatching {
                            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            barcodeScanner.process(inputImage)
                                .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                                .addOnFailureListener { error ->
                                    runOnUiThread {
                                        binding.scanStatusText.text = "二维码识别失败：${error.message}"
                                    }
                                }
                                .addOnCompleteListener {
                                    isProcessingFrame = false
                                    imageProxy.close()
                                }
                        }.onFailure { error ->
                            isProcessingFrame = false
                            imageProxy.close()
                            runOnUiThread {
                                binding.scanStatusText.text = "二维码识别启动失败：${error.message}"
                            }
                        }
                    }
                }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onSuccess {
                binding.scanStatusText.text = "扫码预览已启动，请对准主控端房间二维码。"
            }.onFailure { error ->
                binding.scanStatusText.text = "扫码相机启动失败：${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        if (hasResult) return
        val roomCode = barcodes
            .asSequence()
            .mapNotNull { barcode -> RoomQrScanResultParser.parseRoomCode(barcode.rawValue) }
            .firstOrNull()
        if (roomCode == null) {
            runOnUiThread {
                binding.scanStatusText.text = "已看到二维码，但没有识别到 6 位房间码。"
            }
            return
        }
        hasResult = true
        runOnUiThread {
            Toast.makeText(this, "已识别房间码：${RoomCodeFormatter.display(roomCode)}", Toast.LENGTH_SHORT).show()
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_ROOM_CODE, roomCode),
        )
        finish()
    }

    override fun onDestroy() {
        barcodeScanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROOM_CODE = "extra_room_code"
    }
}
