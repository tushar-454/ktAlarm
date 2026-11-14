package expo.modules.ktalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Registers the code for a barcode mission. Uses the same [BarcodeScannerView] as the mission,
 * so anything that registers here will scan at alarm time.
 *
 * Result: RESULT_OK with [EXTRA_VALUE] + [EXTRA_FORMAT], or RESULT_CANCELED (with
 * [EXTRA_ERROR] = [ERROR_PERMISSION] if the camera permission was denied).
 */
class ScanActivity : Activity() {

  companion object {
    const val EXTRA_VALUE = "expo.modules.ktalarm.SCAN_VALUE"
    const val EXTRA_FORMAT = "expo.modules.ktalarm.SCAN_FORMAT"
    const val EXTRA_ERROR = "expo.modules.ktalarm.SCAN_ERROR"
    const val ERROR_PERMISSION = "permission"
    private const val REQUEST_CAMERA = 1
    /**
     * Same value this many frames in a row before it's accepted. A misread code registered here
     * would make the alarm impossible to turn off, so be sure.
     */
    private const val CONFIRM_READS = 3

    fun intent(context: Context): Intent = Intent(context, ScanActivity::class.java)
  }

  private lateinit var scanner: BarcodeScannerView
  private lateinit var statusView: TextView
  private lateinit var torchButton: Button
  private var torchOn = false
  private var delivered = false
  private var candidate: String? = null
  private var reads = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(buildLayout())
    scanner.onCode = { value, format -> onCode(value, format) }
    scanner.onError = { message -> showError("$message. Close this screen and try again.") }
    scanner.onOpened = {
      showHint()
      torchButton.visibility = if (scanner.hasTorch) View.VISIBLE else View.GONE
    }
    if (!CameraView.hasCameraPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }
  }

  override fun onResume() {
    super.onResume()
    if (CameraView.hasCameraPermission(this) && !delivered) scanner.start()
  }

  override fun onPause() {
    scanner.stop()
    setTorch(false)
    super.onPause()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQUEST_CAMERA) return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      // onResume follows and starts the camera.
      return
    }
    setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, ERROR_PERMISSION))
    finish()
  }

  private fun onCode(value: String, format: String) {
    if (delivered) return
    if (value == candidate) reads++ else {
      candidate = value
      reads = 1
    }
    if (reads < CONFIRM_READS) return
    delivered = true
    scanner.stop()
    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value).putExtra(EXTRA_FORMAT, format))
    finish()
  }

  private fun setTorch(on: Boolean) {
    torchOn = on
    scanner.setTorch(on)
    torchButton.text = if (on) "Flashlight off" else "Flashlight"
  }

  private fun showHint() {
    statusView.text = "Hold the code steady in the frame"
    statusView.setTextColor(Ui.MUTED)
  }

  private fun showError(message: String) {
    statusView.text = message
    statusView.setTextColor(Ui.ERROR)
    torchButton.visibility = View.GONE
  }

  private fun dp(v: Int) = Ui.dp(this, v)

  private fun buildLayout(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setBackgroundColor(Ui.BG)
    }
    root.setOnApplyWindowInsetsListener { v, insets ->
      val base = dp(24)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val i = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(base + i.left, base + i.top, base + i.right, base + i.bottom)
      } else {
        @Suppress("DEPRECATION")
        v.setPadding(
          base + insets.systemWindowInsetLeft,
          base + insets.systemWindowInsetTop,
          base + insets.systemWindowInsetRight,
          base + insets.systemWindowInsetBottom,
        )
      }
      insets
    }

    root.addView(Ui.text(this, 22f, bold = true, value = "Register a code"))
    root.addView(
      Ui.text(
        this,
        15f,
        Ui.MUTED,
        value = "Scan a barcode or QR code somewhere away from your bed — toothpaste, shampoo, a coffee bag. " +
          "You'll have to scan the same code to turn the alarm off.",
      ),
      LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    scanner = BarcodeScannerView(this)
    root.addView(
      FrameLayout(this).apply {
        background = Ui.rounded(this@ScanActivity, Ui.SURFACE, radiusDp = 24)
        clipToOutline = true
        addView(scanner, FrameLayout.LayoutParams(-1, -1))
      },
      LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20) },
    )

    statusView = Ui.text(this, 15f, Ui.MUTED, value = "Starting camera…")
    root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

    torchButton = Ui.secondaryButton(this, "Flashlight") { setTorch(!torchOn) }
    torchButton.visibility = View.GONE
    root.addView(torchButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })

    root.addView(
      Ui.secondaryButton(this, "Cancel") {
        setResult(RESULT_CANCELED)
        finish()
      },
      LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) },
    )
    return root
  }
}
