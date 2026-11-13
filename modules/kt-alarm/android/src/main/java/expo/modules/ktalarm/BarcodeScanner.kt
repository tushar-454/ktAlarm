package expo.modules.ktalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live camera preview that reports every barcode / QR code it sees (CameraX + bundled ML Kit).
 * Used by both the barcode mission and code registration, so a code that registers is a code
 * that scans.
 *
 * Owns its own lifecycle, so it works inside the plain [android.app.Activity]s of this module.
 */
internal class BarcodeScannerView(context: Context) : FrameLayout(context) {

  companion object {
    private const val TAG = "KtAlarm"

    fun hasCameraPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasCamera(context: Context): Boolean =
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun formatName(format: Int): String = when (format) {
      Barcode.FORMAT_QR_CODE -> "QR_CODE"
      Barcode.FORMAT_EAN_13 -> "EAN_13"
      Barcode.FORMAT_EAN_8 -> "EAN_8"
      Barcode.FORMAT_UPC_A -> "UPC_A"
      Barcode.FORMAT_UPC_E -> "UPC_E"
      Barcode.FORMAT_CODE_128 -> "CODE_128"
      Barcode.FORMAT_CODE_39 -> "CODE_39"
      Barcode.FORMAT_CODE_93 -> "CODE_93"
      Barcode.FORMAT_CODABAR -> "CODABAR"
      Barcode.FORMAT_ITF -> "ITF"
      Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
      Barcode.FORMAT_PDF417 -> "PDF417"
      Barcode.FORMAT_AZTEC -> "AZTEC"
      else -> "UNKNOWN"
    }
  }

  /** Called on the main thread for each code seen (repeatedly while it stays in view). */
  var onCode: ((value: String, format: String) -> Unit)? = null
  /** Called on the main thread when the camera can't be opened. */
  var onError: ((message: String) -> Unit)? = null

  private val preview = PreviewView(context).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
  }
  private var lifecycle: ScannerLifecycle? = null
  private var camera: Camera? = null
  private var analysisExecutor: ExecutorService? = null
  private var scanner: MlKitScanner? = null

  init {
    addView(preview, LayoutParams(-1, -1))
  }

  val hasTorch: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

  fun setTorch(on: Boolean) {
    camera?.cameraControl?.enableTorch(on)
  }

  fun start() {
    if (lifecycle != null) return
    if (!hasCameraPermission(context)) {
      onError?.invoke("Camera permission is off")
      return
    }
    val owner = ScannerLifecycle().also { lifecycle = it }
    val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
    val client = BarcodeScanning.getClient().also { scanner = it }

    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      if (lifecycle !== owner) return@addListener // stopped meanwhile
      try {
        val provider = future.get()
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
        analysis.setAnalyzer(executor) { proxy -> analyze(client, proxy) }
        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
        owner.resume()
      } catch (e: Exception) {
        Log.w(TAG, "Cannot start camera", e)
        onError?.invoke("Couldn't open the camera")
      }
    }, ContextCompat.getMainExecutor(context))
  }

  fun stop() {
    lifecycle?.destroy()
    lifecycle = null
    camera = null
    analysisExecutor?.shutdown()
    analysisExecutor = null
    scanner?.close()
    scanner = null
  }

  @androidx.annotation.OptIn(ExperimentalGetImage::class)
  private fun analyze(client: MlKitScanner, proxy: androidx.camera.core.ImageProxy) {
    val media = proxy.image
    if (media == null) {
      proxy.close()
      return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    client.process(input)
      .addOnSuccessListener { codes ->
        val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() } ?: return@addOnSuccessListener
        if (lifecycle != null) onCode?.invoke(code.rawValue!!, formatName(code.format))
      }
      .addOnCompleteListener { proxy.close() }
  }

  /** Minimal LifecycleOwner so CameraX can run without an AndroidX activity. */
  private class ScannerLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun resume() {
      registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      if (registry.currentState != Lifecycle.State.INITIALIZED) {
        registry.currentState = Lifecycle.State.DESTROYED
      }
    }
  }
}
