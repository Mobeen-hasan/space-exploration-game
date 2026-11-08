package com.mobeen.space

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException

/** Draws planets as shaded, procedurally textured spheres with an atmosphere glow. */
class PlanetRenderer : Disposable {
    private val shader: ShaderProgram
    private val white: Texture

    init {
        ShaderProgram.pedantic = false
        shader = ShaderProgram(VERTEX, FRAGMENT)
        if (!shader.isCompiled) throw GdxRuntimeException("Planet shader failed: " + shader.log)
        val pm = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pm.setColor(1f, 1f, 1f, 1f)
        pm.fill()
        white = Texture(pm)
        pm.dispose()
    }

    /** Call between batch.begin() and batch.end(). Light comes from direction (lx, ly). */
    fun draw(batch: SpriteBatch, planet: Planet, time: Float, lx: Float, ly: Float) {
        batch.shader = shader
        shader.setUniformf("u_c0", planet.c0.r, planet.c0.g, planet.c0.b)
        shader.setUniformf("u_c1", planet.c1.r, planet.c1.g, planet.c1.b)
        shader.setUniformf("u_c2", planet.c2.r, planet.c2.g, planet.c2.b)
        shader.setUniformf("u_atmo", planet.atmoColor.r, planet.atmoColor.g, planet.atmoColor.b)
        shader.setUniformf("u_seed", planet.noiseSeed)
        shader.setUniformf("u_time", time)
        shader.setUniformf("u_spin", planet.spin)
        shader.setUniformf("u_quad", QUAD)
        shader.setUniformf("u_light", lx, ly, 0.55f)
        val half = planet.radius * QUAD
        batch.draw(white, planet.x - half, planet.y - half, half * 2f, half * 2f)
        batch.flush() // uniforms change per planet, so draw each one on its own
    }

    override fun dispose() {
        shader.dispose()
        white.dispose()
    }

    companion object {
        /** The drawn quad is this many times the planet radius, leaving room for the glow. */
        const val QUAD = 1.35f

        private const val VERTEX = """
attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;
uniform mat4 u_projTrans;
varying vec2 v_uv;
void main() {
    v_uv = a_texCoord0;
    gl_Position = u_projTrans * a_position;
}
"""

        private const val FRAGMENT = """
#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif
varying vec2 v_uv;
uniform vec3 u_c0;
uniform vec3 u_c1;
uniform vec3 u_c2;
uniform vec3 u_atmo;
uniform vec3 u_light;
uniform float u_seed;
uniform float u_time;
uniform float u_spin;
uniform float u_quad;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i + vec3(0.0, 0.0, 0.0)), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
            mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
        mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
            mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
        f.z);
}

float fbm(vec3 p) {
    float a = 0.5;
    float s = 0.0;
    for (int i = 0; i < 4; i++) {
        s += a * noise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return s;
}

void main() {
    vec2 p = (v_uv - 0.5) * 2.0 * u_quad;
    float r = length(p);
    vec3 L = normalize(u_light);
    vec3 col;
    float alpha;
    if (r < 1.0) {
        vec3 n = vec3(p, sqrt(1.0 - r * r));
        float ang = u_time * u_spin;
        float ca = cos(ang);
        float sa = sin(ang);
        vec3 q = vec3(ca * n.x + sa * n.z, n.y, -sa * n.x + ca * n.z);
        float h = fbm(q * 2.2 + u_seed);
        float h2 = fbm(q * 6.0 + u_seed * 1.7);
        float t = clamp(h * 1.6 - 0.3 + (h2 - 0.5) * 0.35, 0.0, 1.0);
        vec3 base = t < 0.5 ? mix(u_c0, u_c1, t * 2.0) : mix(u_c1, u_c2, (t - 0.5) * 2.0);
        float diff = clamp(dot(n, L), 0.0, 1.0);
        col = base * (0.10 + 0.90 * diff);
        float rim = pow(1.0 - n.z, 2.5);
        col += u_atmo * rim * (0.20 + 0.80 * diff);
        alpha = 1.0;
    } else {
        float g = 1.0 - smoothstep(1.0, u_quad, r);
        g = pow(g, 2.2);
        vec2 dir = p / r;
        float side = 0.35 + 0.65 * clamp(dot(dir, L.xy) * 0.5 + 0.5, 0.0, 1.0);
        col = u_atmo;
        alpha = g * 0.65 * side;
    }
    gl_FragColor = vec4(col, alpha);
}
"""
    }
}
