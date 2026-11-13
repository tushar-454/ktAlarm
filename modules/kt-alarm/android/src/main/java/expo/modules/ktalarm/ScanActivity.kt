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
import android.widget.FrameLayout
import android.widget.LinearLayout

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

    fun intent(context: Context): Intent = Intent(context, ScanActivity::class.java)
  }

  private lateinit var scanner: BarcodeScannerView
  private var torchOn = false
  private var delivered = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(buildLayout())
    scanner.onCode = { value, format -> deliver(value, format) }
    if (!BarcodeScannerView.hasCameraPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }
  }

  override fun onResume() {
    super.onResume()
    if (BarcodeScannerView.hasCameraPermission(this)) scanner.start()
  }

  override fun onPause() {
    scanner.stop()
    super.onPause()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQUEST_CAMERA) return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      scanner.start()
    } else {
      setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, ERROR_PERMISSION))
      finish()
    }
  }

  private fun deliver(value: String, format: String) {
    if (delivered) return
    delivered = true
    scanner.stop()
    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value).putExtra(EXTRA_FORMAT, format))
    finish()
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

    val torch = Ui.secondaryButton(this, "Flashlight") {}
    torch.setOnClickListener {
      torchOn = !torchOn
      scanner.setTorch(torchOn)
      torch.text = if (torchOn) "Flashlight off" else "Flashlight"
    }
    root.addView(torch, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })

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
