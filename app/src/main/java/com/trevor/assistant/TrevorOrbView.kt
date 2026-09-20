package com.trevor.assistant

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Native OpenGL ES 2.0 3D orb with touch rotation, pinch zoom and state animation. */
class TrevorOrbView(context: Context) : GLSurfaceView(context) {
    private val renderer = OrbRenderer()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoom = (renderer.zoom * detector.scaleFactor).coerceIn(0.72f, 1.8f)
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = true
    }

    fun setAccent(argb: Int) { renderer.setAccent(argb) }
    fun setAnimated(enabled: Boolean) { renderer.animated = enabled }
    fun setState(state: TrevorOrbState) { renderer.state = state }
    fun resetView() { renderer.resetView() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                renderer.yaw += (event.x - lastX) * 0.45f
                renderer.pitch = (renderer.pitch + (event.y - lastY) * 0.45f).coerceIn(-80f, 80f)
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private class OrbRenderer : Renderer {
        var yaw = 0f
        var pitch = 8f
        var zoom = 1f
        var animated = true
        var state = TrevorOrbState.IDLE

        private var accentR = 0.345f
        private var accentG = 0.85f
        private var accentB = 1f
        private var program = 0
        private var positionHandle = 0
        private var normalHandle = 0
        private var mvpHandle = 0
        private var colorHandle = 0
        private var lightHandle = 0
        private var intensityHandle = 0
        private var pulseHandle = 0
        private var stateHandle = 0
        private var vertexBuffer: FloatBuffer? = null
        private var normalBuffer: FloatBuffer? = null
        private var indexBuffer: ShortBuffer? = null
        private var indexCount = 0
        private val model = FloatArray(16)
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val mvp = FloatArray(16)
        private var lastTime = System.nanoTime()
        private var elapsed = 0f

        fun setAccent(argb: Int) {
            accentR = ((argb shr 16) and 0xFF) / 255f
            accentG = ((argb shr 8) and 0xFF) / 255f
            accentB = (argb and 0xFF) / 255f
        }

        fun resetView() { yaw = 0f; pitch = 8f; zoom = 1f }

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            program = buildProgram(VERTEX, FRAGMENT)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
            colorHandle = GLES20.glGetUniformLocation(program, "uColor")
            lightHandle = GLES20.glGetUniformLocation(program, "uLight")
            intensityHandle = GLES20.glGetUniformLocation(program, "uIntensity")
            pulseHandle = GLES20.glGetUniformLocation(program, "uPulse")
            stateHandle = GLES20.glGetUniformLocation(program, "uState")
            buildSphere(40, 28)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height.coerceAtLeast(1)
            Matrix.frustumM(projection, 0, -ratio, ratio, -1f, 1f, 2.4f, 12f)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastTime = now
            if (animated) {
                elapsed += dt
                val speed = when (state) {
                    TrevorOrbState.THINKING, TrevorOrbState.ANALYSING, TrevorOrbState.RESEARCHING,
                    TrevorOrbState.PROCESSING_FILE, TrevorOrbState.EXECUTING -> 24f
                    TrevorOrbState.SUCCESS -> 7f
                    TrevorOrbState.ERROR -> 3f
                    else -> 9f
                }
                yaw += dt * speed
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            Matrix.setLookAtM(view, 0, 0f, 0f, 5.1f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, zoom, zoom, zoom)
            Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
            GLES20.glUniform4f(colorHandle, accentR, accentG, accentB, 1f)
            GLES20.glUniform3f(lightHandle, -0.45f, 0.7f, 1f)
            val pulse = if (animated) (sin(elapsed * when (state) {
                TrevorOrbState.LISTENING -> 7f
                TrevorOrbState.THINKING, TrevorOrbState.ANALYSING, TrevorOrbState.RESEARCHING,
                TrevorOrbState.PROCESSING_FILE, TrevorOrbState.EXECUTING -> 4.5f
                TrevorOrbState.SUCCESS -> 2f
                TrevorOrbState.ERROR -> 1.2f
                else -> 1.8f
            }) * 0.5f + 0.5f) else 0.5f
            GLES20.glUniform1f(pulseHandle, pulse)
            GLES20.glUniform1f(stateHandle, state.ordinal.toFloat())
            GLES20.glUniform1f(intensityHandle, when (state) {
                TrevorOrbState.SUCCESS -> 1.35f
                TrevorOrbState.ERROR -> 0.82f
                TrevorOrbState.IDLE -> 0.96f
                TrevorOrbState.LISTENING -> 1.12f + pulse * 0.3f
                else -> 1.05f + pulse * 0.24f
            })
            drawSphere()
        }

        private fun buildSphere(slices: Int, stacks: Int) {
            val vertices = ArrayList<Float>()
            val normals = ArrayList<Float>()
            for (stack in 0..stacks) {
                val v = stack.toFloat() / stacks
                val phi = Math.PI * v
                val y = cos(phi).toFloat()
                val r = sin(phi).toFloat()
                for (slice in 0..slices) {
                    val theta = Math.PI * 2.0 * slice / slices
                    val x = (r * cos(theta)).toFloat()
                    val z = (r * sin(theta)).toFloat()
                    vertices.add(x); vertices.add(y); vertices.add(z)
                    normals.add(x); normals.add(y); normals.add(z)
                }
            }
            val indices = ArrayList<Short>()
            for (stack in 0 until stacks) for (slice in 0 until slices) {
                val a = (stack * (slices + 1) + slice).toShort()
                val b = (a.toInt() + slices + 1).toShort()
                val c = (b.toInt() + 1).toShort()
                val d = (a.toInt() + 1).toShort()
                indices.add(a); indices.add(b); indices.add(d); indices.add(d); indices.add(b); indices.add(c)
            }
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { vertices.forEach(::put); position(0) }
            normalBuffer = ByteBuffer.allocateDirect(normals.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { normals.forEach(::put); position(0) }
            indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { indices.forEach(::put); position(0) }
            indexCount = indices.size
        }

        private fun drawSphere() {
            vertexBuffer?.let { GLES20.glEnableVertexAttribArray(positionHandle); GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, it) }
            normalBuffer?.let { GLES20.glEnableVertexAttribArray(normalHandle); GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, it) }
            indexBuffer?.let { GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, it) }
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(normalHandle)
        }

        private fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            return GLES20.glCreateProgram().also { p ->
                GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
                val linked = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
                check(linked[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(p) }
            }
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val ok = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            uniform mat4 uMvp;
            uniform vec3 uLight;
            varying float vLight;
            void main() {
                vec3 n = normalize(aNormal);
                vLight = max(0.22, dot(n, normalize(uLight)) * 0.62 + 0.38);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uIntensity;
            uniform float uPulse;
            uniform float uState;
            varying float vLight;
            void main() {
                vec3 n = normalize(vec3(
                    sign(vLight - 0.5) * 0.001 + 0.001,
                    0.7,
                    1.0
                ));
                vec3 ice = vec3(0.66, 0.94, 1.0);
                vec3 stateTint = uColor.rgb;
                if (uState > 7.5) stateTint = vec3(1.0, 0.20, 0.28);
                else if (uState > 6.5) stateTint = vec3(0.25, 1.0, 0.70);
                else if (uState > 4.5) stateTint = mix(uColor.rgb, vec3(0.30, 0.72, 1.0), 0.38);
                else if (uState > 3.5) stateTint = mix(uColor.rgb, vec3(0.58, 0.40, 1.0), 0.34);

                float crystal = 0.78 + 0.22 * abs(sin((vLight + uPulse) * 12.0));
                float rim = 0.86 + uPulse * 0.30;
                vec3 base = mix(ice, stateTint, 0.58);
                vec3 lit = base * vLight * uIntensity * rim * crystal;
                float edgeGlow = pow(1.0 - vLight, 2.0) * (0.35 + uPulse * 0.35);
                lit += stateTint * edgeGlow;
                lit += vec3(0.08, 0.18, 0.22);
                gl_FragColor = vec4(lit, 0.94);
            }
        """
    }
}
