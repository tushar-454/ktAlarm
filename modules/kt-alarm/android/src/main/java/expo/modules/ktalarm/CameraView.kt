package expo.modules.ktalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live camera preview + frame analysis (CameraX), the base of the barcode and squat cameras.
 *
 * Owns its own lifecycle so it runs inside this module's plain [android.app.Activity]s. Call
 * [start] when the screen is shown and [stop] when it is hidden: Android doesn't let apps use
 * the camera from the background, so a camera left "running" behind a turned-off screen would
 * just be dead when the user comes back.
 *
 * It never throws. Every failure (no permission, no camera, camera busy or broken, a detector
 * that can't load, frames that stop arriving) ends the session and is reported once through
 * [onError], so a mission can fall back instead of crashing the alarm screen or leaving the
 * user stuck in front of a black preview.
 */
internal abstract class CameraView(context: Context, private val preferFront: Boolean) : FrameLayout(context) {

  companion object {
    private const val TAG = "KtAlarm"
    private const val WATCHDOG_INTERVAL_MS = 2_000L
    /** No frame for this long (from [start], or since the last one) = the camera isn't working. */
    private const val NO_FRAMES_TIMEOUT_MS = 10_000L
    /**
     * One detection taking this long = the detector is stuck. Much longer than [NO_FRAMES_TIMEOUT_MS]:
     * the first detection loads the model, which takes several seconds on slow phones, and the
     * camera holds back new frames until it's done.
     */
    private const val DETECTION_TIMEOUT_MS = 30_000L
    /** The detector failing on this many frames in a row = it isn't going to work. */
    private const val MAX_FAILED_FRAMES = 30

    fun hasCameraPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasCamera(context: Context): Boolean =
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** A camera mission can run right now. */
    fun isAvailable(context: Context): Boolean = hasCamera(context) && hasCameraPermission(context)
  }

  /** Main thread, at most once per [start]. The camera is already stopped when it's called. */
  var onError: ((message: String) -> Unit)? = null
  /** Main thread, once the camera is open (e.g. to show a flashlight button). */
  var onOpened: (() -> Unit)? = null

  /** Analysis resolution, landscape (sensor orientation). */
  protected abstract val analysisSize: Size
  /** Preview and analysis share it, so detections line up with what's on screen. */
  protected abstract val aspectRatio: AspectRatioStrategy

  /** Creates the detector. Main thread, from [start]. May throw. */
  protected abstract fun openDetector()
  /** Releases the detector. Main thread, from [stop]. */
  protected abstract fun closeDetector()
  /**
   * Analyses one frame on the analysis thread. Returns the detector's task (the frame is closed
   * when it completes), or null to skip the frame. May throw.
   */
  protected abstract fun analyze(image: ImageProxy): Task<*>?

  protected val previewView = PreviewView(context).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  /** The running camera session, null when stopped. Also read from the analysis thread. */
  @Volatile private var session: Session? = null

  /** Started and not failed. */
  protected val isRunning: Boolean get() = session != null

  /** The front camera is in use (its preview is mirrored). */
  var isFrontCamera = false
    private set

  val hasTorch: Boolean get() = session?.camera?.cameraInfo?.hasFlashUnit() == true

  init {
    addView(previewView, LayoutParams(-1, -1))
  }

  private class Session {
    val lifecycle = CameraLifecycle()
    var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
    var analysis: ImageAnalysis? = null
    var useCases: Array<UseCase> = emptyArray()
    var executor: ExecutorService? = null
    @Volatile var lastFrameAt = SystemClock.elapsedRealtime()
    /** When the frame being analysed was handed to the detector; 0 when it is idle. */
    @Volatile var analyzingSince = 0L
    val failedFrames = AtomicInteger()
  }

  private val watchdog = object : Runnable {
    override fun run() {
      val s = session ?: return
      val now = SystemClock.elapsedRealtime()
      val busySince = s.analyzingSince
      when {
        busySince != 0L && now - busySince > DETECTION_TIMEOUT_MS -> fail(s, "Camera detection isn't responding", null)
        busySince == 0L && now - s.lastFrameAt > NO_FRAMES_TIMEOUT_MS -> fail(s, "The camera isn't responding", null)
        else -> mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
      }
    }
  }

  fun start() {
    if (session != null) return
    if (!hasCameraPermission(context)) {
      Log.w(TAG, "Camera permission is off")
      onError?.invoke("Camera permission is off")
      return
    }
    val s = Session()
    session = s
    try {
      openDetector()
      val future = ProcessCameraProvider.getInstance(context)
      future.addListener({ bind(s, future) }, ContextCompat.getMainExecutor(context))
    } catch (e: Exception) {
      fail(s, "Couldn't open the camera", e)
      return
    } catch (e: LinkageError) {
      // A detector whose native library can't load on this device.
      fail(s, "Couldn't open the camera", e)
      return
    }
    mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
  }

  fun stop() {
    mainHandler.removeCallbacks(watchdog)
    val s = session ?: return
    session = null
    try {
      s.analysis?.clearAnalyzer()
      if (s.useCases.isNotEmpty()) s.provider?.unbind(*s.useCases)
    } catch (e: Exception) {
      Log.w(TAG, "Cannot unbind the camera", e)
    }
    s.lifecycle.destroy()
    s.executor?.shutdown()
    try {
      closeDetector()
    } catch (e: Exception) {
      Log.w(TAG, "Cannot close the detector", e)
    }
  }

  fun setTorch(on: Boolean) {
    try {
      session?.camera?.cameraControl?.enableTorch(on)
    } catch (e: Exception) {
      Log.w(TAG, "Cannot switch the flashlight", e)
    }
  }

  private fun bind(s: Session, future: ListenableFuture<ProcessCameraProvider>) {
    if (session !== s) return // stopped meanwhile
    try {
      val provider = future.get()
      val preferred = if (preferFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
      val other = if (preferFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
      val selector = when {
        provider.hasCamera(preferred) -> preferred
        provider.hasCamera(other) -> other
        else -> {
          fail(s, "No camera found", null)
          return
        }
      }
      isFrontCamera = selector === CameraSelector.DEFAULT_FRONT_CAMERA

      val preview = Preview.Builder()
        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatio).build())
        .build()
      preview.setSurfaceProvider(previewView.surfaceProvider)

      val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
          ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio)
            .setResolutionStrategy(
              ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
      val executor = Executors.newSingleThreadExecutor()
      s.executor = executor
      analysis.setAnalyzer(executor) { image -> onFrame(s, image) }

      s.provider = provider
      s.analysis = analysis
      s.useCases = arrayOf(preview, analysis)
      s.camera = provider.bindToLifecycle(s.lifecycle, selector, preview, analysis)
      s.lifecycle.resume()
      onOpened?.invoke()
    } catch (e: Exception) {
      fail(s, "Couldn't open the camera", e)
    }
  }

  /** Analysis thread. */
  private fun onFrame(s: Session, image: ImageProxy) {
    s.lastFrameAt = SystemClock.elapsedRealtime()
    if (session !== s) {
      image.close()
      return
    }
    val task = try {
      analyze(image)
    } catch (e: Exception) {
      Log.w(TAG, "Frame analysis failed", e)
      image.close()
      frameFailed(s, e)
      return
    }
    if (task == null) {
      image.close()
      return
    }
    s.analyzingSince = SystemClock.elapsedRealtime()
    task.whenDone { ok, error ->
      image.close()
      s.analyzingSince = 0L
      s.lastFrameAt = SystemClock.elapsedRealtime()
      if (ok) s.failedFrames.set(0) else frameFailed(s, error)
    }
  }

  /** Main thread, after success or failure. Generic so it can be called on a `Task<*>`. */
  private fun <T> Task<T>.whenDone(block: (ok: Boolean, error: Exception?) -> Unit) {
    addOnCompleteListener { block(it.isSuccessful, it.exception) }
  }

  private fun frameFailed(s: Session, e: Exception?) {
    if (s.failedFrames.incrementAndGet() == MAX_FAILED_FRAMES) {
      mainHandler.post { fail(s, "Camera detection isn't working", e) }
    }
  }

  /** Main thread. Ignored if [s] is no longer the running session (already stopped). */
  private fun fail(s: Session, message: String, e: Throwable?) {
    if (session !== s) return
    Log.w(TAG, "Camera failed: $message", e)
    stop()
    onError?.invoke(message)
  }

  /** Minimal LifecycleOwner so CameraX can run without an AndroidX activity. Main thread only. */
  private class CameraLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun resume() {
      if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      // INITIALIZED → DESTROYED is not a legal move (camera never bound): nothing to tear down.
      if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }
  }
}
