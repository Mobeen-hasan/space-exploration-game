package com.mobeen.space

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

/**
 * Plays the synthesized sounds and music at the volumes in [Settings]. The sounds are generated once at start-up
 * and written to a cache folder (the audio system loads files). If audio is unavailable, everything is silent.
 */
class Audio(private val settings: Settings) {
    private val sounds = EnumMap<SoundId, Sound>(SoundId::class.java)
    private val tracks = EnumMap<Track, Music>(Track::class.java)
    private var current: Track? = null
    private var fade = 0f // 0..1, how far the current track has faded in
    private var outgoing: Music? = null
    private var outFade = 0f
    private var thrustId = -1L
    private var thrustLevel = 0f

    fun load() {
        val audio = Gdx.audio ?: return
        try {
            val dir = cacheDir()
            for (id in SoundId.entries) {
                val f = dir.child("${id.name.lowercase()}.wav")
                f.writeBytes(Synth.wav(Synth.sound(id)), false)
                sounds[id] = audio.newSound(f)
            }
            for (t in Track.entries) {
                val f = dir.child("music-${t.name.lowercase()}.wav")
                f.writeBytes(Synth.wav(Synth.music(t)), false)
                tracks[t] = audio.newMusic(f).apply { isLooping = true; volume = 0f }
            }
        } catch (e: Exception) {
            Gdx.app?.error("Audio", "Sound is off: ${e.message}")
        }
    }

    private fun cacheDir(): FileHandle =
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            Gdx.files.absolute(System.getProperty("java.io.tmpdir")).child("space-exploration-audio")
        } else {
            Gdx.files.local("audio")
        }.also { it.mkdirs() }

    private val sfxVolume get() = settings.master * settings.sfx
    private val musicVolume get() = settings.master * settings.music * 0.6f

    fun play(id: SoundId, volume: Float = 1f, pitch: Float = 1f) {
        val v = volume * sfxVolume
        if (v <= 0f) return
        sounds[id]?.play(v, pitch, 0f)
    }

    /** The engine's rumble, from 0 (off) to 1 (full thrust). */
    fun thrust(level: Float) {
        val s = sounds[SoundId.THRUST] ?: return
        thrustLevel = level
        if (level <= 0.01f || sfxVolume <= 0f) {
            if (thrustId != -1L) s.stop(thrustId)
            thrustId = -1L
            return
        }
        if (thrustId == -1L) thrustId = s.loop(0f)
        s.setVolume(thrustId, level * 0.7f * sfxVolume)
        s.setPitch(thrustId, 0.85f + 0.3f * level)
    }

    fun music(track: Track?) {
        if (track == current) return
        outgoing?.stop()
        outgoing = current?.let { tracks[it] }
        outFade = fade
        current = track
        fade = 0f
        track?.let { t -> tracks[t]?.let { it.volume = 0f; it.play() } }
    }

    /** Fades music in and out. Call once a frame. */
    fun update(dt: Float) {
        fade = min(1f, fade + dt / 2.5f)
        current?.let { tracks[it]?.volume = fade * musicVolume }
        outgoing?.let { m ->
            outFade = max(0f, outFade - dt / 1.2f)
            m.volume = outFade * musicVolume
            if (outFade <= 0f) {
                m.stop()
                outgoing = null
            }
        }
        if (thrustId != -1L) thrust(thrustLevel) // follow volume changes
    }

    fun dispose() {
        sounds.values.forEach { it.dispose() }
        tracks.values.forEach { it.dispose() }
        sounds.clear()
        tracks.clear()
    }
}

/**
 * Where the game asks for sounds. It forwards to the [Audio] player when there is one, and remembers what was
 * asked for, so the tests can hear the game without a sound card.
 */
object Sfx {
    var audio: Audio? = null
    val played = ArrayList<SoundId>()
    var track: Track? = null
        private set

    fun play(id: SoundId, volume: Float = 1f, pitch: Float = 1f) {
        if (played.size > 200) played.subList(0, 100).clear()
        played.add(id)
        audio?.play(id, volume, pitch)
    }

    fun thrust(level: Float) {
        audio?.thrust(level)
    }

    /** Change the background music (null for silence). Also stops the engine, since the scene has changed. */
    fun music(t: Track?) {
        track = t
        thrust(0f)
        audio?.music(t)
    }
}
