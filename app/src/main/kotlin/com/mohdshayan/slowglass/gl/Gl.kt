package com.mohdshayan.slowglass.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A texture-backed framebuffer. [halfFloat] buffers hold linear light; RGBA8 buffers hold gamma. */
class Fbo(val width: Int, val height: Int, val halfFloat: Boolean) {
    val tex: Int
    val fb: Int

    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        if (halfFloat) {
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, width, height)
        } else {
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fb = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        clear()
    }

    val complete: Boolean
        get() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
            return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
        GLES30.glViewport(0, 0, width, height)
    }

    fun clear() {
        bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /** Reads the buffer as RGBA8888, bottom row first, into a new direct buffer. */
    fun readRgba(): ByteBuffer {
        bind()
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        return buf
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fb), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

class GlProgram(vertex: String, fragment: String) {
    val id: Int = link(compile(GLES30.GL_VERTEX_SHADER, vertex), compile(GLES30.GL_FRAGMENT_SHADER, fragment))
    private val locations = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun v2(name: String, x: Float, y: Float) = GLES30.glUniform2f(loc(name), x, y)
    fun v3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun m4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)

    fun release() = GLES30.glDeleteProgram(id)

    private companion object {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                error("shader compile failed: $log")
            }
            return s
        }

        fun link(vs: Int, fs: Int): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glBindAttribLocation(p, 0, "aPos")
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            if (ok[0] == 0) error("program link failed: ${GLES30.glGetProgramInfoLog(p)}")
            return p
        }
    }
}

/** Shaders. Every pass draws one full-screen quad; uUv maps quad coordinates to texture coordinates. */
object Shaders {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uUv;
out vec2 vUv;
void main() {
    vUv = (uUv * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val DITHER = """
uniform float uSeed;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec3 finish(vec3 c) {
    if (uGammaOut > 0.5) c = pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
    // The seed changes every frame, so 8-bit rounding errors average out over a stack instead of
    // piling up in the same direction at the same pixel.
    if (uDither > 0.5) c += (hash(gl_FragCoord.xy + vec2(uSeed, uSeed * 0.618)) - 0.5) / 255.0;
    return c;
}
"""

    /** Camera frame: optionally to linear light, scaled for additive blending, optionally back to gamma. */
    const val OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform float uLinear;
uniform float uScale;
uniform float uGammaOut;
uniform float uDither;
in vec2 vUv;
out vec4 o;
$DITHER
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    if (uLinear > 0.5) c = pow(c, vec3(2.2));
    o = vec4(finish(c * uScale), 1.0);
}
"""

    const val COPY = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uScale;
uniform float uGammaOut;
uniform float uDither;
in vec2 vUv;
out vec4 o;
$DITHER
void main() {
    o = vec4(finish(texture(uTex, vUv).rgb * uScale), 1.0);
}
"""

    /** Max filter for gap bridging, radius up to 3 px. */
    const val DILATE = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform int uR;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 o;
void main() {
    vec3 m = vec3(0.0);
    for (int y = -3; y <= 3; y++) {
        for (int x = -3; x <= 3; x++) {
            if (abs(x) > uR || abs(y) > uR) continue;
            m = max(m, texture(uTex, vUv + vec2(float(x), float(y)) * uTexel).rgb);
        }
    }
    o = vec4(m, 1.0);
}
"""

    /** Mean from the three tree levels; for motion blur, pulled toward the max where the max is bright. */
    const val RESOLVE = """#version 300 es
precision highp float;
uniform sampler2D uA;
uniform sampler2D uS;
uniform sampler2D uB;
uniform sampler2D uM;
uniform vec3 uW;
uniform float uKeep;
uniform int uMotion;
uniform float uGammaOut;
uniform float uDither;
in vec2 vUv;
out vec4 o;
$DITHER
void main() {
    vec3 mean = texture(uA, vUv).rgb * uW.x + texture(uS, vUv).rgb * uW.y + texture(uB, vUv).rgb * uW.z;
    if (uMotion == 1) {
        vec3 m = texture(uM, vUv).rgb;
        float l = dot(m, vec3(0.2126, 0.7152, 0.0722));
        mean = mix(mean, m, uKeep * smoothstep(0.6, 1.0, l));
    }
    o = vec4(finish(mean), 1.0);
}
"""
}

/** The one quad every pass draws, and the texture helpers around it. */
object Quad {
    private var vbo = 0
    private var vao = 0

    fun init() {
        val data = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).rewind()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    fun newOesTexture(): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }
}
