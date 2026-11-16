package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.max
import kotlin.math.min

/**
 * The first thing you see: continue or start a Normal or Hardcore game. The two modes have separate saves.
 * With an [epitaph] it opens on the end of a Hardcore game first.
 */
class TitleScreen(
    private val game: SpaceGame,
    private val state: GameState,
    private var epitaph: String? = null,
) : ScreenAdapter() {
    private val camera = OrthographicCamera()
    private val viewport = ScreenViewport(camera)
    private val shapes = ShapeRenderer().apply { setAutoShapeType(true) }
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val bigFont = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val glyphs = GlyphLayout()
    private val starfield = Starfield(3L)
    private val menu = OverlayMenu("MAIN MENU")

    private var time = 0f

    /** Which new game (true = Hardcore) is waiting for a second press, because it would erase that mode's save. */
    private var confirming: Boolean? = null

    // Read once: which slots hold a game, and a line about each.
    private val normalSummary = SaveStore.summary(hardcore = false)
    private val hardcoreSummary = SaveStore.summary(hardcore = true)

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    private fun modeName(hardcore: Boolean) = if (hardcore) "Hardcore" else "Normal"

    private fun rows(): List<MenuRow> {
        if (epitaph != null) return listOf(MenuRow("Back to the main menu") { epitaph = null })
        val list = ArrayList<MenuRow>()
        normalSummary?.let { list.add(MenuRow("Continue Normal game   ($it)") { continueGame(false) }) }
        hardcoreSummary?.let { list.add(MenuRow("Continue Hardcore game   ($it)") { continueGame(true) }) }
        for (hc in listOf(false, true)) {
            val existing = if (hc) hardcoreSummary else normalSummary
            val label = when {
                confirming == hc -> "Press again to ERASE your ${modeName(hc)} game and start over"
                hc -> "New Hardcore game   (one life: if you die, the game is over)"
                else -> "New Normal game   (fall, and you wake up in your ship)"
            } + if (existing != null && confirming != hc) "   - replaces your saved one" else ""
            list.add(MenuRow(label) { startNew(hc) })
        }
        return list
    }

    private fun continueGame(hardcore: Boolean) {
        if (SaveStore.load(state, hardcore)) game.startPlaying()
    }

    private fun startNew(hardcore: Boolean) {
        val existing = if (hardcore) hardcoreSummary else normalSummary
        if (existing != null && confirming != hardcore) {
            confirming = hardcore
            return
        }
        state.resetForNewGame(hardcore)
        SaveStore.save(state) // only this mode's slot is written
        game.startPlaying()
    }

    override fun render(delta: Float) {
        time += min(delta, 0.05f)
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        menu.open = true // the title menu cannot be closed
        val rows = rows()
        menu.handle(w, h, rows)
        menu.open = true

        Gdx.gl.glClearColor(0.01f, 0.012f, 0.025f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeType.Filled)
        starfield.draw(shapes, w / 2f + time * 25f, h / 2f + time * 6f, 1f, time)
        shapes.end()
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        font.data.setScale(max(0.8f, h / 720f * 1.05f))
        bigFont.data.setScale(max(1.6f, h / 720f * 3.2f))
        batch.projectionMatrix = camera.combined
        batch.begin()
        val ending = epitaph
        val title = if (ending != null) "THE HUNT ENDS" else "SPACE EXPLORATION"
        if (ending != null) bigFont.setColor(0.9f, 0.3f, 0.3f, 1f) else bigFont.setColor(1f, 0.85f, 0.5f, 1f)
        glyphs.setText(bigFont, title)
        bigFont.draw(batch, title, (w - glyphs.width) / 2f, h * 0.9f)
        if (ending != null) {
            font.setColor(0.9f, 0.85f, 0.85f, 1f)
            glyphs.setText(font, ending, font.color, w * 0.8f, com.badlogic.gdx.utils.Align.center, true)
            font.draw(batch, glyphs, w * 0.1f, h * 0.78f)
        } else {
            font.setColor(0.75f, 0.85f, 1f, 0.9f)
            val sub = "A bounty hunter's life among the stars"
            glyphs.setText(font, sub)
            font.draw(batch, sub, (w - glyphs.width) / 2f, h * 0.9f - bigFont.lineHeight)
        }
        batch.end()

        menu.draw(shapes, batch, font, glyphs, camera.combined, w, h, rows)
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
        bigFont.dispose()
    }
}
