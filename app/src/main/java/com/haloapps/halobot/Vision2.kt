package com.haloapps.halobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Extra on-device vision (MediaPipe + LiteRT), run a few times a second on a copy of the camera frame:
 *  - hands: finger-gun "bang" gesture (index + middle together, ring + pinky curled, then a flick)
 *  - objects: food, cups/bottles, wine glasses (COCO classes)
 *  - faces: recognises people you've introduced (GhostFaceNet embeddings), so the robot knows
 *    you and your partner. Nothing leaves the phone.
 */
class Vision2(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onObjects(objs: List<Obj>)
        fun onFingerGun(aiming: Boolean, x: Float, y: Float)
        fun onBang(x: Float, y: Float)
        fun onPerson(name: String?, relation: String?, score: Float)
    }

    data class Obj(val label: String, val score: Float, val box: RectF) // box normalised 0..1, upright, not mirrored

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var gestures: GestureRecognizer? = null
    private var objects: ObjectDetector? = null
    private var faceDet: FaceDetector? = null
    private var embedder: Interpreter? = null
    private var loaded = false
    @Volatile var enabled = true

    private var lastObj = 0L
    private var lastFaceId = 0L
    private var lastHand = 0L

    // gun gesture state
    private var gunSince = 0L
    private var gunTipY = 0f
    private var gunTipYMin = 1f
    private var lastBang = 0L

    fun load() {
        exec.execute {
            try {
                gestures = GestureRecognizer.createFromOptions(ctx, GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(1)
                    .setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.5f).build())
            } catch (e: Exception) { Log.w("HaloBot", "gestures", e) }
            try {
                objects = ObjectDetector.createFromOptions(ctx, ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/efficientdet_lite0.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMaxResults(5).setScoreThreshold(.4f).build())
            } catch (e: Exception) { Log.w("HaloBot", "objects", e) }
            try {
                faceDet = FaceDetector.createFromOptions(ctx, FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/blaze_face_short_range.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(.5f).build())
                val bytes = ctx.assets.open("models/ghostfacenet.tflite").use { it.readBytes() }
                val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                model.put(bytes); model.rewind()
                embedder = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
            } catch (e: Exception) { Log.w("HaloBot", "faces", e) }
            loaded = true
        }
    }

    /** Wants a frame now? Keeps the camera thread from converting frames we would drop anyway. */
    fun wantsFrame(): Boolean = enabled && loaded && !busy.get() && SystemClock.uptimeMillis() - lastHand > 110

    /** [frame] is upright (rotation already applied), not mirrored. Takes ownership of the bitmap. */
    fun submit(frame: Bitmap, faceVisible: Boolean) {
        if (!busy.compareAndSet(false, true)) { frame.recycle(); return }
        exec.execute {
            try { process(frame, faceVisible) } catch (e: Exception) { Log.w("HaloBot", "vision2", e) }
            finally { frame.recycle(); busy.set(false) }
        }
    }

    private fun process(frame: Bitmap, faceVisible: Boolean) {
        val now = SystemClock.uptimeMillis()
        val img = BitmapImageBuilder(frame).build()
        lastHand = now
        gestures?.let { g -> hands(g.recognize(img), now) }
        if (now - lastObj > 700) {
            lastObj = now
            objects?.let { d ->
                val res = d.detect(img)
                val list = res.detections().mapNotNull { det ->
                    val c = det.categories().firstOrNull() ?: return@mapNotNull null
                    val b = det.boundingBox()
                    Obj(c.categoryName(), c.score(), RectF(b.left / frame.width, b.top / frame.height, b.right / frame.width, b.bottom / frame.height))
                }
                main.post { listener.onObjects(list) }
            }
        }
        if (faceVisible && now - lastFaceId > 2500 && people.isNotEmpty()) {
            lastFaceId = now
            val e = embedLargestFace(frame)
            if (e != null) {
                val (p, score) = match(e)
                if (p != null && score > .5f) reinforce(p, e)
                main.post { listener.onPerson(p?.name, p?.relation, score) }
            }
        }
    }

    // ---------------- hands ----------------
    private fun hands(r: com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult, now: Long) {
        val lms = r.landmarks()
        if (lms.isEmpty()) { gunSince = 0; return }
        val l = lms[0]
        fun d(a: Int, b: Int) = hypot((l[a].x() - l[b].x()).toDouble(), (l[a].y() - l[b].y()).toDouble()).toFloat()
        val palm = d(0, 9).coerceAtLeast(.02f)
        // a finger is extended if its tip is much farther from the wrist than its middle joint
        fun extended(tip: Int, pip: Int) = d(0, tip) > d(0, pip) * 1.25f
        val index = extended(8, 6)
        val middle = extended(12, 10)
        val ring = extended(16, 14)
        val pinky = extended(20, 18)
        val together = d(8, 12) < palm * .45f
        val gun = index && middle && !ring && !pinky && together
        val tipX = (l[8].x() + l[12].x()) / 2; val tipY = (l[8].y() + l[12].y()) / 2
        if (gun) {
            if (gunSince == 0L) { gunSince = now; gunTipY = tipY; gunTipYMin = tipY; main.post { listener.onFingerGun(true, tipX, tipY) } }
            gunTipYMin = minOf(gunTipYMin, tipY)
            // "shoot" = a quick upward flick of the fingertips (recoil), or holding aim for 1.6 s
            val flick = gunTipY - tipY > palm * .55f
            if ((flick || now - gunSince > 1600) && now - lastBang > 3000 && now - gunSince > 250) {
                lastBang = now; gunSince = 0
                main.post { listener.onBang(tipX, tipY) }
            }
            gunTipY = gunTipY * .7f + tipY * .3f
        } else if (gunSince != 0L) {
            gunSince = 0
            main.post { listener.onFingerGun(false, tipX, tipY) }
        }
    }

    // ---------------- people ----------------
    class Person(val name: String, val relation: String, val emb: MutableList<FloatArray>)
    private val people = ArrayList<Person>()
    private val prefs = ctx.getSharedPreferences("halobot_people", Context.MODE_PRIVATE)

    init { loadPeople() }

    val peopleList: List<Pair<String, String>> get() = synchronized(people) { people.map { it.name to it.relation } }

    private fun loadPeople() {
        try {
            val loaded = ArrayList<Person>()
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val embs = o.getJSONArray("e")
                val list = ArrayList<FloatArray>()
                for (j in 0 until embs.length()) {
                    val v = embs.getJSONArray(j)
                    list.add(FloatArray(v.length()) { v.getDouble(it).toFloat() })
                }
                loaded.add(Person(o.getString("n"), o.optString("r", "friend"), list))
            }
            synchronized(people) { people.addAll(loaded) }
        } catch (_: Exception) {}
    }

    private fun savePeople() {
        val arr = JSONArray()
        synchronized(people) {
            for (p in people) {
                val e = JSONArray()
                for (v in p.emb.takeLast(20)) e.put(JSONArray().apply { v.forEach { put(it.toDouble()) } })
                arr.put(JSONObject().put("n", p.name).put("r", p.relation).put("e", e))
            }
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun reload() { synchronized(people) { people.clear() }; loadPeople() }

    fun forget(name: String): Boolean {
        val removed = synchronized(people) { people.removeAll { it.name.equals(name, true) } }
        if (removed) savePeople()
        return removed
    }

    /** Learns the largest face in the next few frames as [name]. [done] gets how many samples were taken. */
    private var enrollName: String? = null
    private var enrollRel = "friend"
    private var enrollCount = 0
    private var enrollDone: ((Int) -> Unit)? = null
    fun enroll(name: String, relation: String, done: (Int) -> Unit) {
        enrollName = name; enrollRel = relation; enrollCount = 0; enrollDone = done
        main.postDelayed({ finishEnroll() }, 6000)
    }
    private fun finishEnroll() {
        val cb = enrollDone ?: return
        enrollDone = null
        val n = enrollCount
        enrollName = null
        if (n > 0) savePeople()
        cb(n)
    }

    /** Called with full frames while enrolling (from [submitEnroll]). */
    fun submitEnroll(frame: Bitmap) {
        val name = enrollName
        if (name == null || !loaded) { frame.recycle(); return }
        exec.execute {
            try {
                val e = embedLargestFace(frame)
                if (e != null) {
                    synchronized(people) {
                        val p = people.firstOrNull { it.name.equals(name, true) } ?: Person(name, enrollRel, ArrayList()).also { people.add(it) }
                        p.emb.add(e)
                    }
                    enrollCount++
                    if (enrollCount >= 5) main.post { finishEnroll() }
                }
            } catch (_: Exception) {} finally { frame.recycle() }
        }
    }
    val enrolling: Boolean get() = enrollName != null

    private var lastSave = 0L
    /** Keeps learning a known face from good matches (new angles, lighting, glasses...). */
    private fun reinforce(p: Person, e: FloatArray) {
        synchronized(people) {
            if (p.emb.any { v -> var d = 0f; for (i in v.indices) d += v[i] * e[i]; d > .85f }) return
            p.emb.add(e)
            while (p.emb.size > 20) p.emb.removeAt(3)
        }
        val t = SystemClock.uptimeMillis()
        if (t - lastSave > 60_000) { lastSave = t; savePeople() }
    }

    private fun match(e: FloatArray): Pair<Person?, Float> {
        var best: Person? = null; var bestScore = 0f
        synchronized(people) {
            for (p in people) for (v in p.emb) {
                var dot = 0f
                for (i in v.indices) dot += v[i] * e[i]
                if (dot > bestScore) { bestScore = dot; best = p }
            }
        }
        return if (bestScore >= .38f) best to bestScore else null to bestScore
    }

    private val pixels = IntArray(112 * 112)
    private val input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(512) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun embedLargestFace(bmp: Bitmap): FloatArray? {
        val det = faceDet ?: return null
        val emb = embedder ?: return null
        val res = det.detect(BitmapImageBuilder(bmp).build())
        val d = res.detections().maxByOrNull { it.boundingBox().width() * it.boundingBox().height() } ?: return null
        val box = d.boundingBox()
        var angle = 0f
        val kps = d.keypoints().orElse(null)
        if (kps != null && kps.size >= 2) {
            val a = kps[0]; val b = kps[1]
            val (l, r) = if (a.x() <= b.x()) a to b else b to a
            angle = Math.toDegrees(atan2(((r.y() - l.y()) * bmp.height).toDouble(), ((r.x() - l.x()) * bmp.width).toDouble())).toFloat()
        }
        val side = max(box.width(), box.height()).coerceAtLeast(8f)
        val out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(-box.centerX(), -box.centerY())
        m.postRotate(-angle)
        m.postScale(112 / side, 112 / side)
        m.postTranslate(56f, 56f)
        Canvas(out).drawBitmap(bmp, m, paint)
        out.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        out.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        emb.run(input, output)
        val e = output[0].copyOf()
        var n = 0f
        for (v in e) n += v * v
        n = sqrt(n).coerceAtLeast(1e-6f)
        for (i in e.indices) e[i] /= n
        return e
    }

    fun close() {
        exec.execute {
            try { gestures?.close() } catch (_: Exception) {}
            try { objects?.close() } catch (_: Exception) {}
            try { faceDet?.close() } catch (_: Exception) {}
            try { embedder?.close() } catch (_: Exception) {}
        }
        exec.shutdown()
    }
}
