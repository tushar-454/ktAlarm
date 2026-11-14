package expo.modules.ktalarm

import android.content.Context
import android.util.Size
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner as MlKitScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Back-camera preview that reports every barcode / QR code it sees (bundled ML Kit model, works
 * offline). Used by both the barcode mission and code registration, so a code that registers is a
 * code that scans.
 */
internal class BarcodeScannerView(context: Context) : CameraView(context, preferFront = false) {

  companion object {
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

  /** Main thread, for each code seen (repeatedly while it stays in view). */
  var onCode: ((value: String, format: String) -> Unit)? = null

  // Product barcodes need pixels: ML Kit recommends at least 720p for 1D codes.
  override val analysisSize = Size(1280, 720)
  override val aspectRatio: AspectRatioStrategy = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

  @Volatile private var detector: MlKitScanner? = null

  override fun openDetector() {
    detector = BarcodeScanning.getClient()
  }

  override fun closeDetector() {
    detector?.close()
    detector = null
  }

  @androidx.annotation.OptIn(ExperimentalGetImage::class)
  override fun analyze(image: ImageProxy): Task<*>? {
    val client = detector ?: return null
    val media = image.image ?: return null
    return client.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
      .addOnSuccessListener { codes ->
        if (!isRunning) return@addOnSuccessListener
        val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() } ?: return@addOnSuccessListener
        onCode?.invoke(code.rawValue!!, formatName(code.format))
      }
  }
}
