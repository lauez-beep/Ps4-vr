// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Log
import android.view.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.stream.DebugTrace

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput)
{
	var session: Session? = null
		private set

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state
	private val _rumbleState = MutableLiveData<RumbleEvent>(RumbleEvent(0U, 0U))
	val rumbleState: LiveData<RumbleEvent> get() = _rumbleState

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null

	// Superficie "basura" a la que el decoder sigue mandando video cuando la app pasa a segundo plano.
	// Es un ImageReader que descarta cada imagen: asi el decoder nunca queda apuntando a una pantalla destruida.
	private var dummyReader: ImageReader? = null
	private var dummyThread: HandlerThread? = null

	private fun getDummySurface(): Surface?
	{
		dummyReader?.let { return it.surface }
		return try
		{
			val thread = HandlerThread("chiaki-dummy-surface")
			thread.start()
			val reader = ImageReader.newInstance(
				connectInfo.videoProfile.width, connectInfo.videoProfile.height, ImageFormat.YUV_420_888, 3)
			reader.setOnImageAvailableListener({ r ->
				try { r.acquireLatestImage()?.close() } catch(e: Throwable) {}
			}, Handler(thread.looper))
			dummyThread = thread
			dummyReader = reader
			reader.surface
		}
		catch(e: Throwable)
		{
			Log.e("StreamSession", "No se pudo crear la superficie auxiliar", e)
			null
		}
	}

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
	}

	fun shutdown()
	{
		session?.stop()
		session?.dispose()
		session = null
		_state.value = StreamStateIdle
		//surfaceTexture?.release()
	}

	fun pause()
	{
		shutdown()
	}

	fun resume()
	{
		if(session != null)
			return
		try
		{
			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
			_state.value = StreamStateConnecting
			session.eventCallback = this::eventCallback
			session.start()
			val surface = surface
			if(surface != null)
				session.setSurface(surface)
			this.session = session
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
	}

	private fun eventCallback(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> _state.postValue(StreamStateConnected)
			is QuitEvent -> _state.postValue(
				StreamStateQuit(
					event.reason,
					event.reasonString
				)
			)
			is LoginPinRequestEvent -> _state.postValue(
				StreamStateLoginPinRequest(
					event.pinIncorrect
				)
			)
			is RumbleEvent -> _rumbleState.postValue(event)
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) { }

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
			{
				DebugTrace.log("session: surfaceChanged inicio")
				val surface = holder.surface
				this@StreamSession.surface = surface
				applyFrameRateHint(surface)
				session?.setSurface(surface)
				DebugTrace.log("session: surfaceChanged fin")
			}

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				this@StreamSession.surface = null
				// Cambiar la salida del decoder ANTES de que la pantalla real se destruya
				DebugTrace.log("session: surfaceDestroyed inicio")
				session?.setSurface(getDummySurface())
				DebugTrace.log("session: surfaceDestroyed fin")
			}
		})
	}

	// Le avisa al sistema a cuántos fps llega el video, para que el TV iguale su tasa de refresco
	// (Android 11+; en versiones anteriores no hace nada)
	private fun applyFrameRateHint(surface: Surface)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return
		runCatching {
			surface.setFrameRate(connectInfo.videoProfile.maxFPS.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
		}
	}

	fun attachToSurfaceTexture(texture: SurfaceTexture)
	{
		surfaceTexture = texture
		val surface = Surface(texture)
		this.surface = surface
		session?.setSurface(surface)
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				this@StreamSession.surface = Surface(surfaceTexture)
				session?.setSurface(Surface(surface))
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}
}
