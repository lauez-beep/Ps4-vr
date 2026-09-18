// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView que expone una SurfaceTexture propia (GL_TEXTURE_EXTERNAL_OES)
 * para usar como destino del decoder de Chiaki, y la dibuja dos veces (SBS)
 * con distorsión de barril para visores tipo cardboard.
 *
 * Uso desde StreamActivity:
 *   vrStreamView.onSurfaceTextureReady = { surfaceTexture ->
 *       viewModel.session.attachToSurfaceTexture(surfaceTexture)
 *   }
 */
class VrStreamView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs)
{
	// Llamado en el hilo principal cuando la SurfaceTexture ya existe y está lista para usar
	var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

	// Ajustables en caliente desde la Activity (sliders)
	@Volatile var ipd: Float = 0.02f          // separación horizontal entre ojos, en UV (0 a ~0.05)
	@Volatile var barrelStrength: Float = 0.22f // fuerza de la distorsión de barril

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
		private var textureId = 0
		private var surfaceTexture: SurfaceTexture? = null
		private var program = 0
		private var aPosLoc = 0
		private var uTexLoc = 0
		private var uEyeOffsetLoc = 0
		private var uBarrelLoc = 0

		private val quadVertices = floatArrayOf(
			-1f, -1f,
			 1f, -1f,
			-1f,  1f,
			 1f,  1f
		)
		private lateinit var vertexBuffer: FloatBuffer

		private val vertexShaderSrc = """
			attribute vec2 aPosition;
			varying vec2 vUV;
			void main() {
				vUV = (aPosition + 1.0) * 0.5;
				vUV.x = 1.0 - vUV.x;
				gl_Position = vec4(aPosition, 0.0, 1.0);
			}
		""".trimIndent()

		// samplerExternalOES: obligatorio para leer una SurfaceTexture directamente en GLES
		private val fragmentShaderSrc = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			varying vec2 vUV;
			uniform samplerExternalOES uTexture;
			uniform float uEyeOffset;
			uniform float uBarrel;

			void main() {
				vec2 c = vec2(0.5, 0.5);
				vec2 uv = vUV - c;

				// distorsión de barril: compensa la lente convexa del cardboard
				float r2 = dot(uv, uv);
				uv = uv * (1.0 + uBarrel * r2);

				uv += c;
				uv.x += uEyeOffset;

				if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
					gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
				} else {
					gl_FragColor = texture2D(uTexture, uv);
				}
			}
		""".trimIndent()

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

			// avisar a la Activity en el hilo principal, ya con la textura lista
			mainHandler.post { onSurfaceTextureReady?.invoke(st) }

			program = buildProgram(vertexShaderSrc, fragmentShaderSrc)
			aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
			uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
			uEyeOffsetLoc = GLES20.glGetUniformLocation(program, "uEyeOffset")
			uBarrelLoc = GLES20.glGetUniformLocation(program, "uBarrel")

			val bb = ByteBuffer.allocateDirect(quadVertices.size * 4).order(ByteOrder.nativeOrder())
			vertexBuffer = bb.asFloatBuffer().apply { put(quadVertices); position(0) }
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

			vertexBuffer.position(0)
			GLES20.glEnableVertexAttribArray(aPosLoc)
			GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

			val w = width
			val h = height

			// ojo izquierdo: mitad izquierda de la pantalla
			GLES20.glViewport(0, 0, w / 2, h)
			GLES20.glUniform1f(uEyeOffsetLoc, -ipd)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

			// ojo derecho: mitad derecha
			GLES20.glViewport(w / 2, 0, w / 2, h)
			GLES20.glUniform1f(uEyeOffsetLoc, ipd)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

			GLES20.glDisableVertexAttribArray(aPosLoc)
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

		// dimensiones actuales del viewport, actualizadas en onSurfaceChanged
		private var width = 0
		private var height = 0
	}
