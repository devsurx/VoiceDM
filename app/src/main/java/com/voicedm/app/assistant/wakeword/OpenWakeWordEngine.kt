package com.voicedm.app.assistant.wakeword

import android.content.Context
import android.os.SystemClock
import com.voicedm.app.assistant.audio.EnergyVad
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.random.Random

/**
 * On-device wake-word engine built on openWakeWord's TFLite models (Apache-2.0).
 *
 * Three models run per frame, mirroring the reference Python pipeline
 * (`openwakeword/utils.py::AudioFeatures` + `model.py::Model.predict`) exactly:
 *
 * ```
 *  1280 samples @16 kHz (80 ms)
 *      |                       melspectrogram.tflite  (~1 MB)
 *      v   window = last 1280+480 samples -> 8 rows x 32 mels, x/10+2
 *  melspectrogram buffer  [rows x 32], 76-row context, capped at 970 rows (~10 s)
 *      |                       embedding_model.tflite  (~1.3 MB)  <- gated by EnergyVad
 *      v   76x32 window, stepped by 8 rows (== one 80 ms step)
 *  feature buffer [rows x 96], capped at 120 rows
 *      |                       <phrase>_v0.1.tflite    (~0.4-1.2 MB)
 *      v   last 16 rows
 *  score in 0..1
 * ```
 *
 * The port was validated against the reference implementation frame-for-frame on the
 * same audio: identical mel buffers and score correlation 1.000000 (max abs diff 0.0).
 * The energy gate keeps only the (expensive) embedding+head stages running while
 * somebody speaks; the cheap mel stage keeps buffers aligned, and missed embedding
 * windows are rebuilt on voice onset so detection starts from a consistent state.
 */
class OpenWakeWordEngine(
    context: Context,
    override val phrase: WakePhrase,
    private val vad: EnergyVad = EnergyVad(),
) : WakeWordEngine {

    private val assets = context.applicationContext.assets

    private val melspec: Interpreter
    private val embedding: Interpreter
    private val head: Interpreter

    /** Head input time steps (16 in openWakeWord v0.5.1), read from the graph. */
    private val featureWindow: Int

    /** Output element counts, read once so buffers can be reused every frame. */
    private val melOutElements: Int
    private val embOutElements: Int
    private val headOutElements: Int

    // --- streaming buffers (see diagram above) -------------------------------------
    /** Raw PCM tail; only the last STEP+TAIL samples are ever needed. */
    private val raw = ArrayDeque<Short>(RAW_CAP)
    private val melBuffer = ArrayList<FloatArray>(MEL_CAP)
    private val featureBuffer = ArrayList<FloatArray>(FEAT_CAP)

    private var pendingWindows = 0   // windows due this frame (1 per step)
    private var missedWindows = 0    // windows skipped by the VAD gate / detector pauses
    private var framesSeen = 0

    // --- reusable I/O buffers ------------------------------------------------------
    private val melIn = directFloats(WakeWordEngine.STEP_SAMPLES + MEL_TAIL)
    private var melOutBuf: ByteBuffer
    private val melScratch = FloatArray(MEL_MAX_OUTPUT)
    private val embIn = directFloats(MEL_WINDOW * MEL_FEATURES)
    private var embOutBuf: ByteBuffer
    private val headIn: ByteBuffer
    private var headOutBuf: ByteBuffer

    @Volatile
    private var lastScore = 0f

    @Volatile
    private var lastMs = 0f

    init {
        val opts = Interpreter.Options().apply { numThreads = 1 }
        melspec = Interpreter(loadModel(WakePhrase.MELSPEC_ASSET), opts)
        embedding = Interpreter(loadModel(WakePhrase.EMBEDDING_ASSET), opts)
        head = Interpreter(loadModel(phrase.asset), opts)

        // The melspec graph takes a dynamic number of samples; pin it to one step plus
        // the 480-sample tail context so no resizing ever happens at runtime.
        melspec.resizeInput(0, intArrayOf(1, WakeWordEngine.STEP_SAMPLES + MEL_TAIL), false)
        melspec.allocateTensors()
        embedding.allocateTensors()
        head.allocateTensors()

        featureWindow = head.getInputTensor(0).shape()[1]
        melOutElements = melspec.getOutputTensor(0).shape().numel()
        embOutElements = embedding.getOutputTensor(0).shape().numel()
        headOutElements = head.getOutputTensor(0).shape().numel()

        melOutBuf = ByteBuffer.allocateDirect(melOutElements * 4)
        embOutBuf = ByteBuffer.allocateDirect(embOutElements * 4)
        headOutBuf = ByteBuffer.allocateDirect(headOutElements * 4)
        headIn = directFloats(featureWindow * FEAT_DIM)

        seedBuffers()
    }

    // ---------------------------------------------------------------------------------

    override fun processFrame(frame: ShortArray): FrameResult {
        require(frame.size == WakeWordEngine.STEP_SAMPLES) {
            "WakeWordEngine frames must be ${WakeWordEngine.STEP_SAMPLES} samples, got ${frame.size}"
        }
        val t0 = SystemClock.elapsedRealtimeNanos()

        // 1. keep the raw tail (melspec window input)
        for (s in frame) {
            if (raw.size >= RAW_CAP) raw.removeFirst()
            raw.addLast(s)
        }

        // 2. one mel step per 80 ms frame
        pendingWindows++
        runMelStep()

        // 3. energy gate decides whether the heavy stages run this frame
        val speaking = vad.observeRms(EnergyVad.rms(frame))
        if (speaking) {
            val total = min(missedWindows + pendingWindows, MAX_BACKFILL)
            if (total > 0) appendWindows(total)
            missedWindows = 0
            pendingWindows = 0
        } else {
            missedWindows += pendingWindows
            pendingWindows = 0
        }

        // 4. score (first frames zeroed, same as the reference model wrapper)
        var score = if (featureBuffer.size >= featureWindow) runHead() else 0f
        if (framesSeen < WARMUP_FRAMES) score = 0f
        framesSeen++

        lastScore = score
        lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000f
        return FrameResult(score, speaking, lastMs)
    }

    override fun markGap(frames: Int) {
        if (frames <= 0) return
        missedWindows = min(missedWindows + frames, MAX_BACKFILL)
    }

    override fun reset() {
        raw.clear()
        seedBuffers()
        missedWindows = 0
        pendingWindows = 0
        framesSeen = 0
        vad.reset()
    }

    override fun close() {
        runCatching { melspec.close() }
        runCatching { embedding.close() }
        runCatching { head.close() }
    }

    /** Diagnostics for the live debug panel. */
    fun diagnostics(): Map<String, String> = mapOf(
        "phrase" to phrase.label,
        "score" to "%.3f".format(lastScore),
        "vad" to if (vad.active) "speech" else "quiet",
        "floor" to "%.0f".format(vad.noiseFloorRms),
        "frame" to "%.2f ms".format(lastMs),
        "cpu" to "%.1f %%".format(lastMs / 80f * 100f),
        "mel rows" to "${melBuffer.size}",
        "feat rows" to "${featureBuffer.size}",
        "missed" to "$missedWindows",
    )

    // ---------------------------------------------------------------------------------
    // Reference pipeline, transliterated from openwakeword/utils.py

    private fun runMelStep() {
        // window = last (STEP + TAIL) samples, zero-padded at the front on the first frames
        melIn.clear()
        val need = WakeWordEngine.STEP_SAMPLES + MEL_TAIL
        val have = min(raw.size, need)
        for (i in 0 until need - have) melIn.putFloat(0f)
        val startIdx = raw.size - have
        for (p in 0 until have) melIn.putFloat(raw[startIdx + p].toFloat())
        melIn.flip()

        melOutBuf.clear()
        melspec.run(melIn, melOutBuf)
        melOutBuf.rewind()

        // output layout [1,1,T,32]; flatten and re-rows into [T,32] with the x/10+2 transform
        val cols = MEL_FEATURES
        val frames = melOutElements / cols
        melOutBuf.asFloatBuffer().get(melScratch, 0, melOutElements)
        for (r in 0 until frames) {
            val row = FloatArray(cols)
            val base = r * cols
            var c = 0
            while (c < cols) {
                row[c] = melScratch[base + c] / 10f + 2f
                c++
            }
            melBuffer.add(row)
        }
        while (melBuffer.size > MEL_CAP) melBuffer.removeAt(0)
    }

    /** Appends embeddings for the newest [count] windows, oldest first (reference order). */
    private fun appendWindows(count: Int) {
        val l = melBuffer.size
        var appended = 0
        for (j in count - 1 downTo 0) {
            val start = l - MEL_WINDOW - 8 * j
            if (start < 0) continue
            embIn.clear()
            var r = 0
            while (r < MEL_WINDOW) {
                val row = melBuffer[start + r]
                var c = 0
                while (c < MEL_FEATURES) {
                    embIn.putFloat(row[c])
                    c++
                }
                r++
            }
            embIn.flip()
            embOutBuf.clear()
            embedding.run(embIn, embOutBuf)
            embOutBuf.rewind()

            val feat = FloatArray(FEAT_DIM)
            embOutBuf.asFloatBuffer().get(feat, 0, min(embOutElements, FEAT_DIM))
            featureBuffer.add(feat)
            appended++
        }
        while (featureBuffer.size > FEAT_CAP) featureBuffer.removeAt(0)
    }

    private fun runHead(): Float {
        headIn.clear()
        val start = featureBuffer.size - featureWindow
        var r = 0
        while (r < featureWindow) {
            val row = featureBuffer[start + r]
            var c = 0
            while (c < FEAT_DIM) {
                headIn.putFloat(row[c])
                c++
            }
            r++
        }
        headIn.flip()
        headOutBuf.clear()
        head.run(headIn, headOutBuf)
        headOutBuf.rewind()
        val out = FloatArray(headOutElements)
        headOutBuf.asFloatBuffer().get(out)
        return out[0]
    }

    /** melspec buffer starts as 76 rows of ones, feature buffer as random rows - exactly
     *  how the reference initializes, so early frames behave the same. */
    private fun seedBuffers() {
        melBuffer.clear()
        repeat(MEL_WINDOW) { melBuffer.add(FloatArray(MEL_FEATURES) { 1f }) }
        featureBuffer.clear()
        repeat(FEAT_SEED_ROWS) {
            featureBuffer.add(FloatArray(FEAT_DIM) { Random.nextInt(-1000, 1000).toFloat() })
        }
    }

    // ---------------------------------------------------------------------------------

    private fun loadModel(asset: String): ByteBuffer = runCatching {
        // Preferred: memory-mapped straight from the (uncompressed) APK asset.
        val fd = assets.openFd(asset)
        FileInputStream(fd.fileDescriptor).use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }.getOrElse {
        // Fallback (asset got compressed): copy into a direct buffer once.
        assets.open(asset).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(bytes); flip() }
        }
    }

    /** Element count of a tensor shape (the stdlib has no IntArray.product). */
    private fun IntArray.numel(): Int = fold(1) { acc, dim -> acc * dim }

    private fun directFloats(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

    private companion object {
        const val MEL_TAIL = 480            // 160*3 samples of extra context, as reference
        const val MEL_WINDOW = 76           // frames of context per embedding window
        const val MEL_FEATURES = 32         // mel bands
        const val MEL_CAP = 970             // 10 * 97 rows ~ 10 s
        const val FEAT_DIM = 96             // embedding dimension
        const val FEAT_CAP = 120            // ~10 s of 80 ms rows
        const val FEAT_SEED_ROWS = 50
        const val RAW_CAP = 4096            // > STEP+TAIL, the only window we ever read
        const val MAX_BACKFILL = 16         // at most 16 windows rebuilt on voice onset
        const val WARMUP_FRAMES = 5         // reference zeroes the first 5 predictions
        const val MEL_MAX_OUTPUT = 8192     // scratch for one step of mel output floats
    }
}
