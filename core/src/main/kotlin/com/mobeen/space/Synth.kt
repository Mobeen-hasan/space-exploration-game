package com.mobeen.space

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Every sound effect in the game. All of them are synthesized by [Synth]: there are no audio files. */
enum class SoundId { CLICK, BUY, COIN, SCAN, LAND, CRASH, HIT, HURT, MISS, HEAL, PICKUP, STAIRS, DOOR, COLLAPSE, THRUST }

/** Background music: lounge jazz in space, wind on planets, a drone in the ruins. */
enum class Track { SPACE, SURFACE, RUINS }

/** Builds sounds and music as 16-bit mono samples, and wraps them as WAV files. Pure maths, so it can be tested. */
object Synth {
    const val RATE = 22050
    private const val TAU = (2.0 * PI).toFloat()

    // ------------------------------------------------------------------ building blocks

    private fun hz(midi: Int): Float = 440f * 2f.pow((midi - 69) / 12f)

    private fun len(seconds: Float) = (seconds * RATE).toInt()

    private fun lowpassCoef(fc: Float) = 1f - exp(-TAU * fc / RATE)

    /** Adds a decaying tone into [buf] at [start]. Writes wrap around, so loops join without a gap. */
    private fun tone(
        buf: FloatArray, start: Int, freq: Float, seconds: Float, amp: Float,
        decay: Float, attack: Float = 0.005f, harmonics: FloatArray = floatArrayOf(1f), glideTo: Float = freq,
        wrap: Boolean = false, tremolo: Float = 0f,
    ) {
        val n = len(seconds)
        val seam = if (wrap && n >= buf.size) SEAM else 0 // a full-length loop overlaps itself and crossfades
        var phase = 0f
        for (i in 0 until n + seam) {
            val t = i / RATE.toFloat()
            val f = freq + (glideTo - freq) * (i / n.toFloat())
            phase += TAU * f / RATE
            var s = 0f
            for ((k, a) in harmonics.withIndex()) s += a * sin(phase * (k + 1))
            val env = (if (t < attack) t / attack else 1f) * exp(-t / decay) * (1f - tremolo * (0.5f + 0.5f * sin(TAU * 5f * t))) *
                seamGain(i, n, seam)
            val idx = start + i
            if (wrap) buf[idx.mod(buf.size)] += s * amp * env
            else if (idx in buf.indices) buf[idx] += s * amp * env
        }
    }

    /** Adds filtered noise: [lowHz] and [highHz] shape it, [env] gives its loudness over time (0..1). */
    private fun noise(
        buf: FloatArray, start: Int, seconds: Float, amp: Float, rnd: Random,
        lowHz: Float = 8000f, highHz: Float = 0f, wrap: Boolean = false, env: (Float) -> Float,
    ) {
        val n = len(seconds)
        val seam = if (wrap && n >= buf.size) SEAM else 0
        val a = lowpassCoef(lowHz)
        val b = if (highHz > 0f) lowpassCoef(highHz) else 0f
        var lp = 0f
        var hp = 0f
        for (i in 0 until n + seam) {
            lp += a * (rnd.nextFloat() * 2f - 1f - lp)
            hp += b * (lp - hp)
            val s = (lp - hp) * amp * env((i % n) / n.toFloat()) * seamGain(i, n, seam)
            val idx = start + i
            if (wrap) buf[idx.mod(buf.size)] += s
            else if (idx in buf.indices) buf[idx] += s
        }
    }

    private val SEAM = (0.3f * RATE).toInt()

    /** Fades a full-length loop in over its first [seam] samples and out over the [seam] samples past its end. */
    private fun seamGain(i: Int, n: Int, seam: Int): Float = when {
        seam == 0 -> 1f
        i < seam -> i / seam.toFloat()
        i >= n -> (n + seam - i) / seam.toFloat()
        else -> 1f
    }

    /** Scales [buf] so its loudest sample sits at [peak], and converts it to 16-bit samples. */
    private fun finish(buf: FloatArray, peak: Float = 0.85f): ShortArray {
        var max = 1e-6f
        for (s in buf) if (abs(s) > max) max = abs(s)
        val k = peak / max
        return ShortArray(buf.size) { (buf[it] * k * 32767f).toInt().coerceIn(-32767, 32767).toShort() }
    }

    // ------------------------------------------------------------------ sound effects

    fun sound(id: SoundId): ShortArray {
        val rnd = Random(id.ordinal * 977L + 13L)
        return when (id) {
            SoundId.CLICK -> FloatArray(len(0.05f)).also { tone(it, 0, 1250f, 0.05f, 1f, 0.012f, 0.001f) }.let { finish(it, 0.5f) }
            SoundId.BUY -> FloatArray(len(0.3f)).also {
                tone(it, 0, 988f, 0.2f, 0.8f, 0.07f, harmonics = floatArrayOf(1f, 0.3f))
                tone(it, len(0.08f), 1319f, 0.22f, 0.8f, 0.09f, harmonics = floatArrayOf(1f, 0.3f))
            }.let { finish(it, 0.6f) }
            SoundId.COIN -> FloatArray(len(0.35f)).also {
                tone(it, 0, 1568f, 0.3f, 0.7f, 0.08f, harmonics = floatArrayOf(1f, 0.2f, 0.1f))
                tone(it, len(0.06f), 2093f, 0.3f, 0.7f, 0.1f, harmonics = floatArrayOf(1f, 0.2f))
            }.let { finish(it, 0.55f) }
            SoundId.SCAN -> FloatArray(len(0.45f)).also { b ->
                listOf(660f, 880f, 1320f).forEachIndexed { i, f -> tone(b, len(0.09f * i), f, 0.15f, 0.7f, 0.06f, harmonics = floatArrayOf(1f, 0f, 0.25f)) }
            }.let { finish(it, 0.6f) }
            SoundId.LAND -> FloatArray(len(0.45f)).also {
                tone(it, 0, 110f, 0.4f, 1f, 0.12f, 0.002f, glideTo = 45f)
                noise(it, 0, 0.3f, 0.8f, rnd, lowHz = 600f) { t -> (1f - t).pow(3) }
            }.let { finish(it, 0.8f) }
            SoundId.CRASH -> FloatArray(len(0.8f)).also {
                noise(it, 0, 0.7f, 1f, rnd, lowHz = 2500f) { t -> (1f - t).pow(2.5f) }
                tone(it, 0, 90f, 0.5f, 0.8f, 0.15f, 0.002f, glideTo = 40f)
                for (f in listOf(233f, 377f, 611f, 987f)) tone(it, 0, f, 0.7f, 0.18f, 0.25f, 0.001f)
            }.let { finish(it, 0.9f) }
            SoundId.HIT -> FloatArray(len(0.18f)).also {
                noise(it, 0, 0.08f, 1f, rnd, lowHz = 3000f) { t -> 1f - t }
                tone(it, 0, 180f, 0.16f, 0.9f, 0.05f, 0.001f, glideTo = 70f)
            }.let { finish(it, 0.8f) }
            SoundId.HURT -> FloatArray(len(0.35f)).also { b ->
                tone(b, 0, 130f, 0.3f, 1.2f, 0.1f, 0.002f, harmonics = floatArrayOf(1f, 0.5f, 0.3f), glideTo = 60f)
                noise(b, 0, 0.15f, 0.6f, rnd, lowHz = 1500f) { t -> 1f - t }
                for (i in b.indices) b[i] = tanh(b[i] * 2f) // a crunchy edge
            }.let { finish(it, 0.8f) }
            SoundId.MISS -> FloatArray(len(0.25f)).also {
                noise(it, 0, 0.25f, 1f, rnd, lowHz = 2500f, highHz = 600f) { t -> sin(PI.toFloat() * t) }
            }.let { finish(it, 0.45f) }
            SoundId.HEAL -> FloatArray(len(0.7f)).also { b ->
                listOf(523f, 659f, 784f, 1047f).forEachIndexed { i, f -> tone(b, len(0.07f * i), f, 0.5f, 0.5f, 0.25f, 0.01f) }
            }.let { finish(it, 0.55f) }
            SoundId.PICKUP -> FloatArray(len(0.2f)).also {
                tone(it, 0, 1046f, 0.12f, 0.8f, 0.05f)
                tone(it, len(0.05f), 1568f, 0.14f, 0.8f, 0.06f)
            }.let { finish(it, 0.55f) }
            SoundId.STAIRS -> FloatArray(len(0.9f)).also { b ->
                for (i in 0..3) {
                    tone(b, len(0.2f * i), 140f - i * 18f, 0.18f, 1f, 0.05f, 0.002f, glideTo = 60f)
                    noise(b, len(0.2f * i), 0.06f, 0.5f, rnd, lowHz = 1200f) { t -> 1f - t }
                }
            }.let { finish(it, 0.7f) }
            SoundId.DOOR -> FloatArray(len(0.6f)).also {
                noise(it, 0, 0.55f, 1f, rnd, lowHz = 4000f, highHz = 400f) { t -> (if (t < 0.1f) t * 10f else 1f) * (1f - t).pow(1.5f) }
                tone(it, 0, 70f, 0.5f, 0.4f, 0.3f, 0.05f)
            }.let { finish(it, 0.5f) }
            SoundId.COLLAPSE -> FloatArray(len(1.8f)).also {
                tone(it, 0, 330f, 1.7f, 0.8f, 0.9f, 0.02f, harmonics = floatArrayOf(1f, 0.4f, 0.2f), glideTo = 82f, tremolo = 0.4f)
                tone(it, 0, 165f, 1.7f, 0.6f, 1.0f, 0.02f, glideTo = 41f)
            }.let { finish(it, 0.8f) }
            SoundId.THRUST -> FloatArray(len(1f)).also { // loops seamlessly while the engine burns
                noise(it, 0, 1f, 1f, rnd, lowHz = 380f, wrap = true) { 1f }
                tone(it, 0, 55f, 1f, 0.25f, 1e9f, 0f, harmonics = floatArrayOf(1f, 0.5f), wrap = true)
            }.let { finish(it, 0.6f) }
        }
    }

    // ------------------------------------------------------------------ music

    fun music(track: Track): ShortArray = when (track) {
        Track.SPACE -> lounge()
        Track.SURFACE -> wind()
        Track.RUINS -> drone()
    }

    private class Chord(val root: Int, val tones: IntArray) // tones in semitones above the root

    /** A slow, smoky jazz loop: walking bass, electric piano, brushes on the ride, and a few vibraphone notes. */
    private fun lounge(): ShortArray {
        val beat = 60f / 88f
        val m7 = intArrayOf(3, 7, 10)
        val dom7 = intArrayOf(4, 7, 10)
        val maj7 = intArrayOf(4, 7, 11)
        val half = intArrayOf(3, 6, 10)
        // D minor: i - iv - ii-V, around twice with a turn to the relative major
        val bars = listOf(
            Chord(50, m7), Chord(50, m7), Chord(55, m7), Chord(55, m7),
            Chord(52, half), Chord(57, dom7), Chord(50, m7), Chord(57, dom7),
            Chord(58, maj7), Chord(58, maj7), Chord(55, m7), Chord(48, dom7),
            Chord(53, maj7), Chord(52, half), Chord(57, dom7), Chord(57, dom7),
        )
        val buf = FloatArray(len(beat * 4 * bars.size))
        val rnd = Random(88)
        val swing = beat * 0.66f
        for ((b, ch) in bars.withIndex()) {
            val bar0 = beat * 4 * b
            val next = bars[(b + 1) % bars.size].root
            // walking bass: root, a chord tone, the fifth, then a step into the next chord
            val walk = listOf(ch.root, ch.root + ch.tones[if (rnd.nextBoolean()) 0 else 1], ch.root + 7, next + if (rnd.nextBoolean()) 1 else -1)
            for ((i, note) in walk.withIndex()) {
                tone(buf, len(bar0 + beat * i), hz(note - 24), beat * 1.1f, 0.5f, 0.35f, 0.006f, floatArrayOf(1f, 0.45f, 0.15f), wrap = true)
            }
            // electric piano stabs on two and four, sometimes pushed early
            for (hit in listOf(1, 3)) {
                val at = bar0 + beat * hit - (if (rnd.nextFloat() < 0.3f) beat - swing else 0f)
                for (t in ch.tones) tone(buf, len(at), hz(ch.root + 12 + t - 12), beat * 1.6f, 0.12f, 0.5f, 0.008f, floatArrayOf(1f, 0.25f, 0.08f), wrap = true, tremolo = 0.2f)
            }
            // brushes on the ride: ding, ding-a, ding, ding-a
            for (i in 0 until 4) {
                noise(buf, len(bar0 + beat * i), 0.18f, 0.07f, rnd, lowHz = 9000f, highHz = 5000f, wrap = true) { t -> (1f - t).pow(3) }
                if (i % 2 == 1) noise(buf, len(bar0 + beat * i + swing), 0.12f, 0.05f, rnd, lowHz = 9000f, highHz = 5000f, wrap = true) { t -> (1f - t).pow(3) }
            }
            // a few vibraphone notes over the second half
            if (b >= 8) for (i in 0 until 8) {
                if (rnd.nextFloat() > 0.28f) continue
                val note = ch.root + 24 + ch.tones[rnd.nextInt(ch.tones.size)]
                tone(buf, len(bar0 + beat * (i / 2) + (if (i % 2 == 1) swing else 0f)), hz(note), 1.4f, 0.1f, 0.6f, 0.004f, floatArrayOf(1f, 0f, 0f, 0.15f), wrap = true, tremolo = 0.35f)
            }
        }
        return finish(buf, 0.7f)
    }

    /** Wind over open ground, with a slow, distant pad. */
    private fun wind(): ShortArray {
        val seconds = 32f
        val buf = FloatArray(len(seconds))
        val rnd = Random(41)
        noise(buf, 0, seconds, 1f, rnd, lowHz = 500f, highHz = 60f, wrap = true) { t ->
            0.35f + 0.25f * sin(TAU * t * 3f) + 0.15f * sin(TAU * t * 7f + 1f)
        }
        for ((i, note) in listOf(50, 57, 60, 64).withIndex()) { // D, A, C, E: open and lonely
            tone(buf, len(i * 0.8f), hz(note), seconds, 0.12f, 1e9f, 0f, floatArrayOf(1f, 0.2f), wrap = true, tremolo = 0.25f)
        }
        return finish(buf, 0.5f)
    }

    /** Low, beating tones, rumbling dark, and a bell somewhere far off. */
    private fun drone(): ShortArray {
        val seconds = 32f
        val buf = FloatArray(len(seconds))
        val rnd = Random(66)
        tone(buf, 0, hz(38), seconds, 0.4f, 1e9f, 0f, floatArrayOf(1f, 0.6f, 0.3f), wrap = true)
        tone(buf, 0, hz(38) * 1.006f, seconds, 0.3f, 1e9f, 0f, floatArrayOf(1f, 0.4f), wrap = true) // beats against the first
        tone(buf, 0, hz(45) * 0.997f, seconds, 0.12f, 1e9f, 0f, floatArrayOf(1f, 0.3f), wrap = true)
        noise(buf, 0, seconds, 0.7f, rnd, lowHz = 180f, wrap = true) { t -> 0.5f + 0.5f * sin(TAU * t * 2f) }
        for (at in listOf(5f, 17.5f, 26f)) { // a cracked bell: partials that don't quite agree
            for ((k, ratio) in listOf(1f, 2.76f, 5.4f, 8.93f).withIndex()) {
                tone(buf, len(at), hz(62) * ratio, 5f, 0.08f / (k + 1), 1.6f, 0.002f, wrap = true)
            }
        }
        return finish(buf, 0.6f)
    }

    // ------------------------------------------------------------------ files

    fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val out = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        for (s in samples) out.putShort(s)
        return out.array()
    }
}
