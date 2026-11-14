package expo.modules.ktalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Size
import android.view.View
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.max

/** One body landmark, in pixels of the upright camera frame. */
internal class PosePoint(val x: Float, val y: Float, val likelihood: Float)

/** The body found in one camera frame (upright, not mirrored). */
internal class PoseFrame(val width: Int, val height: Int, private val points: Map<Int, PosePoint>) {

  companion object {
    /** ML Kit's in-frame likelihood below which a landmark is a guess (off-screen / hidden). */
    const val MIN_LIKELIHOOD = 0.5f

    val BONES = listOf(
      PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
      PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
      PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
      PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
      PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
      PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
      PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
      PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
      PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
      PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
      PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
      PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
    )
    val JOINTS = BONES.flatMap { listOf(it.first, it.second) }.distinct()

    /** Null when nobody is in the frame. */
    fun from(pose: Pose, width: Int, height: Int): PoseFrame? {
      val landmarks = pose.allPoseLandmarks
      if (landmarks.isEmpty()) return null
      val points = landmarks
        .filter { it.landmarkType in JOINTS }
        .associate { it.landmarkType to PosePoint(it.position.x, it.position.y, it.inFrameLikelihood) }
      return PoseFrame(width, height, points)
    }
  }

  fun visible(type: Int): PosePoint? = points[type]?.takeIf { it.likelihood >= MIN_LIKELIHOOD }

  /** Legs whose hip, knee and ankle are all in view. */
  fun legs(): List<SquatCounter.Leg> = listOfNotNull(
    leg(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
    leg(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE),
  )

  private fun leg(hip: Int, knee: Int, ankle: Int): SquatCounter.Leg? {
    val h = visible(hip) ?: return null
    val k = visible(knee) ?: return null
    val a = visible(ankle) ?: return null
    return SquatCounter.Leg(SquatCounter.Point(h.x, h.y), SquatCounter.Point(k.x, k.y), SquatCounter.Point(a.x, a.y))
  }
}

/**
 * Front-camera preview (falls back to the back camera) that finds the user's body in every frame
 * with ML Kit pose detection (bundled model, works offline) and draws their skeleton on top, so
 * they can see the camera is tracking them.
 */
internal class PoseCameraView(context: Context) : CameraView(context, preferFront = true) {

  /** Main thread, for every analysed frame. Null = nobody in view. */
  var onPose: ((PoseFrame?) -> Unit)? = null

  // Pose detection doesn't need many pixels, and smaller frames keep it fast on cheap phones.
  override val analysisSize = Size(640, 480)
  override val aspectRatio: AspectRatioStrategy = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

  @Volatile private var detector: PoseDetector? = null
  private val skeleton = SkeletonOverlay(context)

  init {
    addView(skeleton, LayoutParams(-1, -1))
  }

  fun showPose(frame: PoseFrame?, highlight: Boolean) {
    skeleton.show(frame, mirrored = isFrontCamera, highlight = highlight)
  }

  override fun openDetector() {
    detector = PoseDetection.getClient(
      PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
    )
  }

  override fun closeDetector() {
    detector?.close()
    detector = null
    skeleton.show(null, mirrored = false, highlight = false)
  }

  @androidx.annotation.OptIn(ExperimentalGetImage::class)
  override fun analyze(image: ImageProxy): Task<*>? {
    val client = detector ?: return null
    val media = image.image ?: return null
    val rotation = image.imageInfo.rotationDegrees
    // ML Kit returns landmarks in the upright (rotated) frame.
    val sideways = rotation % 180 != 0
    val width = if (sideways) image.height else image.width
    val height = if (sideways) image.width else image.height
    return client.process(InputImage.fromMediaImage(media, rotation))
      .addOnSuccessListener { pose ->
        if (isRunning) onPose?.invoke(PoseFrame.from(pose, width, height))
      }
  }
}

/** Draws the detected skeleton over a FILL_CENTER preview of the same aspect ratio. */
internal class SkeletonOverlay(context: Context) : View(context) {
  private var frame: PoseFrame? = null
  private var mirrored = false

  private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = Ui.dp(context, 5).toFloat()
    strokeCap = Paint.Cap.ROUND
  }
  private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val jointRadius = Ui.dp(context, 5).toFloat()

  fun show(frame: PoseFrame?, mirrored: Boolean, highlight: Boolean) {
    this.frame = frame
    this.mirrored = mirrored
    bonePaint.color = if (highlight) Ui.SUCCESS else Ui.ACCENT
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val f = frame ?: return
    if (f.width <= 0 || f.height <= 0) return
    val scale = max(width / f.width.toFloat(), height / f.height.toFloat())
    val dx = (width - f.width * scale) / 2f
    val dy = (height - f.height * scale) / 2f
    fun x(p: PosePoint) = (dx + p.x * scale).let { if (mirrored) width - it else it }
    fun y(p: PosePoint) = dy + p.y * scale

    for ((a, b) in PoseFrame.BONES) {
      val pa = f.visible(a) ?: continue
      val pb = f.visible(b) ?: continue
      canvas.drawLine(x(pa), y(pa), x(pb), y(pb), bonePaint)
    }
    for (type in PoseFrame.JOINTS) {
      val p = f.visible(type) ?: continue
      canvas.drawCircle(x(p), y(p), jointRadius, jointPaint)
    }
  }
}
