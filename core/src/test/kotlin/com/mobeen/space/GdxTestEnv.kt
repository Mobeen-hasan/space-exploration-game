package com.mobeen.space

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.Preferences
import com.badlogic.gdx.utils.GdxNativesLoader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.IntBuffer

/**
 * A fake libGDX environment for tests: no window and no GPU. Graphics calls are accepted and ignored,
 * so the real screens can be rendered frame by frame and driven with simulated key presses.
 */
object GdxTestEnv {
    val pending = ArrayList<Runnable>()
    var pressed: Set<Int> = emptySet() // keys "just pressed" this frame
    var held: Set<Int> = emptySet() // keys held down
    var delta = 1f / 60f
    var flushCount = 0

    /** Saved preferences by file name, kept in memory so tests can check what was saved where. */
    val saved = HashMap<String, HashMap<String, Any?>>()
    private val prefsByName = HashMap<String, Preferences>()

    fun resetSaves() {
        saved.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun prefsFor(name: String): Preferences = prefsByName.getOrPut(name) {
        proxy(Preferences::class.java) { method, a ->
            val store = saved.getOrPut(name) { HashMap() }
            when (method) {
                "flush" -> { flushCount++; null }
                "put" -> { store.putAll(a[0] as Map<String, Any?>); null }
                "putString", "putBoolean", "putInteger", "putLong", "putFloat" -> { store[a[0] as String] = a[1]; null }
                "contains" -> store.containsKey(a[0] as String)
                "get" -> HashMap(store)
                "getString" -> (store[a[0] as String] as? String) ?: (a.getOrNull(1) as? String ?: "")
                "remove" -> { store.remove(a[0] as String); null }
                "clear" -> { store.clear(); null }
                else -> unhandled
            }
        }
    }

    private var installed = false
    private val unhandled = Any()

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(cls: Class<T>, handler: (String, Array<Any?>) -> Any?): T {
        val h = InvocationHandler { p, m, args ->
            val a = args ?: emptyArray()
            when (m.name) {
                "hashCode" -> return@InvocationHandler System.identityHashCode(p)
                "equals" -> return@InvocationHandler p === a[0]
                "toString" -> return@InvocationHandler "stub"
            }
            val r = handler(m.name, a)
            if (r !== unhandled) r else defaultFor(m.returnType)
        }
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), h) as T
    }

    private fun defaultFor(t: Class<*>): Any? = when (t) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Boolean::class.javaPrimitiveType -> false
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> 0.toChar()
        Map::class.java -> emptyMap<String, Any>()
        String::class.java -> ""
        else -> null
    }

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        GdxNativesLoader.load()

        val gl = proxy(GL20::class.java) { name, a ->
            when (name) {
                "glCreateShader", "glCreateProgram", "glGenBuffer", "glGenTexture", "glGenFramebuffer" -> 1
                "glGetShaderiv", "glGetProgramiv" -> {
                    val pname = a[1] as Int
                    if (pname == 0x8B81 || pname == 0x8B82) (a[2] as IntBuffer).put(0, 1) // compile / link OK
                    null
                }
                else -> unhandled
            }
        }
        Gdx.gl = gl
        Gdx.gl20 = gl

        Gdx.app = proxy(Application::class.java) { name, a ->
            when (name) {
                "postRunnable" -> { pending.add(a[0] as Runnable); null }
                "getType" -> Application.ApplicationType.Desktop
                "getPreferences" -> prefsFor(a[0] as String)
                else -> unhandled
            }
        }
        Gdx.graphics = proxy(Graphics::class.java) { name, _ ->
            when (name) {
                "getWidth", "getBackBufferWidth" -> 1280
                "getHeight", "getBackBufferHeight" -> 720
                "getDensity" -> 1f
                "getDeltaTime" -> delta
                else -> unhandled
            }
        }
        Gdx.input = proxy(Input::class.java) { name, a ->
            when (name) {
                "isKeyPressed" -> (a[0] as Int) in held
                "isKeyJustPressed" -> (a[0] as Int) in pressed
                else -> unhandled
            }
        }
        Gdx.files = HeadlessFiles()
    }
}

/** Drives a real [SpaceGame] one frame at a time, the way the desktop launcher would. */
class GameHarness(devMode: Boolean = false, startOnTitle: Boolean = false) {
    val game = SpaceGame(devMode, startOnTitle)
    val state get() = game.state
    val screen get() = game.screen

    init {
        GdxTestEnv.install()
        GdxTestEnv.resetSaves()
        GdxTestEnv.pending.clear()
        GdxTestEnv.pressed = emptySet()
        GdxTestEnv.held = emptySet()
        game.create()
    }

    /** One frame: queued screen switches run first, then the current screen renders. */
    fun frame(count: Int = 1) {
        repeat(count) {
            val queued = GdxTestEnv.pending.toList()
            GdxTestEnv.pending.clear()
            queued.forEach { it.run() }
            game.render()
        }
    }

    fun press(key: Int) {
        GdxTestEnv.pressed = setOf(key)
        frame()
        GdxTestEnv.pressed = emptySet()
    }

    fun hold(key: Int, frames: Int) {
        GdxTestEnv.held = setOf(key)
        frame(frames)
        GdxTestEnv.held = emptySet()
    }
}
