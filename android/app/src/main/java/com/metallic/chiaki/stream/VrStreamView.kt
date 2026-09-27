// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * GLSurfaceView que expone una SurfaceTexture propia (GL_TEXTURE_EXTERNAL_OES)
 * para usar como destino del decoder de Chiaki, y la dibuja en modo SBS sobre
 * una pantalla curva (cilíndrica), con distorsión de barril para cardboard.
 */
class VrStreamView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs)
{
	var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

	@Volatile var ipd: Float = 0.02f
	@Volatile var barrelStrength: Float = 0.22f

	private val mainHandler = Handler(Looper.getMainLooper())
	private lateinit var rendererImpl: Renderer_

	init
	{
		setEGLContextClientVersion(2)
		preserveEGLContextOnPause = true
		rendererImpl = Renderer_()
		setRenderer(rendererImpl)
		renderMode = RENDERMODE_CONTINUOUSLY
	}

	private inner class Renderer_ : Renderer
	{
		// --- Geometría de la pantalla curva ---
		private val segments = 32
		private val arcDegrees = 90f     // cuántos grados de arco cubre la pantalla
		private val curveRadius = 3f     // distancia de la pantalla al espectador
		private val halfHeight = 1.19f    // alto medio de la pantalla

		private var textureId = 0
		private var surfaceTexture: SurfaceTexture? = null
		private var program = 0
		private var aPosLoc = 0
		private var aUVLoc = 0
		private var uTexLoc = 0
		private var uMVPLoc = 0
		private var uBarrelLoc = 0

		private lateinit var meshBuffer: FloatBuffer
		private var vertexCount = 0

		private val projMatrix = FloatArray(16)
		private val viewMatrix = FloatArray(16)
		private val mvpMatrix = FloatArray(16)

		private val vertexShaderSrc = """
			uniform mat4 uMVP;
			attribute vec3 aPosition;
			attribute vec2 aUV;
			varying vec2 vUV;
			void main() {
				vUV = aUV;
				gl_Position = uMVP * vec4(aPosition, 1.0);
			}
		""".trimIndent()

		private val fragmentShaderSrc = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			varying vec2 vUV;
			uniform samplerExternalOES uTexture;
			uniform float uBarrel;

			void main() {
				vec2 c = vec2(0.5, 0.5);
				vec2 uv = vUV - c;

				// distorsión de barril: compensa la lente convexa del cardboard
				float r2 = dot(uv, uv);
				uv = uv * (1.0 + uBarrel * r2);
				uv += c;

				if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
					gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
				} else {
					gl_FragColor = texture2D(uTexture, uv);
				}
			}
		""".trimIndent()

		private fun buildMesh()
		{
			val halfArcRad = Math.toRadians(arcDegrees.toDouble() / 2.0)
			val data = ArrayList<Float>((segments + 1) * 2 * 5)
			for(i in 0..segments)
			{
				val t = i.toFloat() / segments
				val theta = -halfArcRad + t * (2.0 * halfArcRad)
				val x = (sin(theta) * curveRadius).toFloat()
				val z = (-cos(theta) * curveRadius).toFloat()
				val u = 1f - t // invertido para que no salga en espejo

				// vértice de arriba (v = 1)
				data.add(x); data.add(halfHeight); data.add(z); data.add(u); data.add(1f)
				// vértice de abajo (v = 0)
				data.add(x); data.add(-halfHeight); data.add(z); data.add(u); data.add(0f)
			}
			vertexCount = (segments + 1) * 2

			val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
			meshBuffer = bb.asFloatBuffer().apply {
				data.forEach { put(it) }
				position(0)
			}
		}

		override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
		{
			GLES20.glClearColor(0f, 0f, 0f, 1f)

			val ids = IntArray(1)
			GLES20.glGenTextures(1, ids, 0)
			textureId = ids[0]
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

			val st = SurfaceTexture(textureId)
			st.setOnFrameAvailableListener { requestRender() }
			surfaceTexture = st

			mainHandler.post { onSurfaceTextureReady?.invoke(st) }

			program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
			aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
			aUVLoc = GLES20.glGetAttribLocation(program, "aUV")
			uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
			uMVPLoc = GLES20.glGetUniformLocation(program, "uMVP")
			uBarrelLoc = GLES20.glGetUniformLocation(program, "uBarrel")

			buildMesh()
		}

		override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
		{
			this.width = width
			this.height = height
			GLES20.glViewport(0, 0, width, height)
		}

		override fun onDrawFrame(gl: GL10?)
		{
			val st = surfaceTexture ?: return
			st.updateTexImage()

			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
			GLES20.glUseProgram(program)

			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
			GLES20.glUniform1i(uTexLoc, 0)
			GLES20.glUniform1f(uBarrelLoc, barrelStrength)

			meshBuffer.position(0)
			GLES20.glEnableVertexAttribArray(aPosLoc)
			GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 20, meshBuffer)

			meshBuffer.position(3)
			GLES20.glEnableVertexAttribArray(aUVLoc)
			GLES20.glVertexAttribPointer(aUVLoc, 2, GLES20.GL_FLOAT, false, 20, meshBuffer)

			val w = width
			val h = height
			val aspect = (w / 2f) / h

			// ojo izquierdo
			GLES20.glViewport(0, 0, w / 2, h)
			drawEye(-ipd / 2f, aspect)

			// ojo derecho
			GLES20.glViewport(w / 2, 0, w / 2, h)
			drawEye(ipd / 2f, aspect)

			GLES20.glDisableVertexAttribArray(aPosLoc)
			GLES20.glDisableVertexAttribArray(aUVLoc)
		}

		private fun drawEye(eyeX: Float, aspect: Float)
		{
			Matrix.perspectiveM(projMatrix, 0, 55f, aspect, 0.05f, 100f)
			Matrix.setLookAtM(viewMatrix, 0, eyeX, 0f, 0f, eyeX, 0f, -1f, 0f, 1f, 0f)
			Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

			GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
		}

		private fun buildProgram(vsSrc: String, fsSrc: String): Int
		{
			val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
			val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
			val prog = GLES20.glCreateProgram()
			GLES20.glAttachShader(prog, vs)
			GLES20.glAttachShader(prog, fs)
			GLES20.glLinkProgram(prog)
			val status = IntArray(1)
			GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
			if(status[0] == 0)
			{
				val log = GLES20.glGetProgramInfoLog(prog)
				GLES20.glDeleteProgram(prog)
				throw RuntimeException("Error al linkear el programa GL: $log")
			}
			return prog
		}

		private fun compileShader(type: Int, src: String): Int
		{
			val shader = GLES20.glCreateShader(type)
			GLES20.glShaderSource(shader, src)
			GLES20.glCompileShader(shader)
			val status = IntArray(1)
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
			if(status[0] == 0)
			{
				val log = GLES20.glGetShaderInfoLog(shader)
				GLES20.glDeleteShader(shader)
				throw RuntimeException("Error al compilar shader ($type): $log")
			}
			return shader
		}

		private var width = 0
		private var height = 0
	}
}
