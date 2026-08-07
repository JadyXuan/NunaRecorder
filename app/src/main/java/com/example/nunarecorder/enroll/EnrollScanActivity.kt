package com.example.nunarecorder.enroll

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫入组二维码。
 *
 * 只做一件事：扫到一个**能通过校验的**入组码就带回去。扫到别的二维码时提示
 * "这不是 EgoAudio 的入组码"并继续扫，而不是把随便什么字符串塞回调用方——
 * 现场研究员需要知道是"扫错了码"还是"这张卡有问题"。
 */
@ExperimentalGetImage
class EnrollScanActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CODE = "enrollment_code"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private lateinit var previewView: PreviewView

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "没有相机权限，请改用粘贴文本码", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)
        title = "扫描入组二维码"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                Toast.makeText(this, "相机不可用，请改用粘贴文本码", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyze(scanner, proxy) } }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure {
                Toast.makeText(this, "相机启动失败，请改用粘贴文本码", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        proxy: androidx.camera.core.ImageProxy
    ) {
        val media = proxy.image
        if (media == null || delivered.get()) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    if (b.format != Barcode.FORMAT_QR_CODE) continue
                    val raw = b.rawValue ?: continue
                    when (val r = EnrollmentCodec.parse(raw)) {
                        is EnrollmentParseResult.Ok -> {
                            // 只在解析通过时才返回。校验失败的码当场说清原因，
                            // 现场才分得清是"扫错了"还是"卡有问题"。
                            if (delivered.compareAndSet(false, true)) {
                                setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(EXTRA_CODE, raw)
                                )
                                finish()
                            }
                            return@addOnSuccessListener
                        }
                        is EnrollmentParseResult.Error -> runOnUiThread {
                            Toast.makeText(this, r.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
