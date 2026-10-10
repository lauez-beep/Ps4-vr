// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Servicio en primer plano que mantiene viva la app (y por tanto la sesion de Remote Play)
 * mientras esta en segundo plano, como un reproductor de musica.
 * La sesion sigue en StreamViewModel; este servicio solo evita que Android mate el proceso,
 * muestra la notificacion y mantiene CPU/WiFi despiertos.
 */
class AudioBackgroundService : Service()
{
	companion object
	{
		private const val CHANNEL_ID = "chiaki_audio"
		private const val NOTIFICATION_ID = 4242
		private const val ACTION_STOP = "com.metallic.chiaki.action.STOP_AUDIO"

		// La Activity lo asigna para poder cerrar la sesion desde la notificacion
		@Volatile var onStopRequested: (() -> Unit)? = null

		fun start(context: Context)
		{
			val intent = Intent(context, AudioBackgroundService::class.java)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				context.startForegroundService(intent)
			else
				context.startService(intent)
		}

		fun stop(context: Context)
		{
			context.stopService(Intent(context, AudioBackgroundService::class.java))
		}
	}

	private var wakeLock: PowerManager.WakeLock? = null
	private var wifiLock: WifiManager.WifiLock? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == ACTION_STOP)
		{
			Handler(Looper.getMainLooper()).post { onStopRequested?.invoke() }
			stopSelf()
			return START_NOT_STICKY
		}

		DebugTrace.add(this, "service: onStartCommand")
		try
		{
			createChannel()
			val notification = buildNotification()
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
				ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
			else
				startForeground(NOTIFICATION_ID, notification)
			DebugTrace.add(this, "service: startForeground OK")
		}
		catch(e: Throwable)
		{
			DebugTrace.addError(this, "startForeground", e)
		}

		try
		{
			acquireLocks()
			DebugTrace.add(this, "service: locks OK")
		}
		catch(e: Throwable)
		{
			DebugTrace.addError(this, "acquireLocks", e)
		}
		return START_NOT_STICKY
	}

	override fun onDestroy()
	{
		releaseLocks()
		super.onDestroy()
	}

	private fun acquireLocks()
	{
		if(wakeLock == null)
		{
			val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chiaki:audio").apply {
				setReferenceCounted(false)
				acquire()
			}
		}
		if(wifiLock == null)
		{
			val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			@Suppress("DEPRECATION")
			val mode = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
				WifiManager.WIFI_MODE_FULL_LOW_LATENCY
			else
				WifiManager.WIFI_MODE_FULL_HIGH_PERF
			wifiLock = wm.createWifiLock(mode, "chiaki:audio").apply {
				setReferenceCounted(false)
				acquire()
			}
		}
	}

	private fun releaseLocks()
	{
		wakeLock?.let { if(it.isHeld) it.release() }
		wakeLock = null
		wifiLock?.let { if(it.isHeld) it.release() }
		wifiLock = null
	}

	private fun createChannel()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return
		val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if(nm.getNotificationChannel(CHANNEL_ID) == null)
		{
			val channel = NotificationChannel(CHANNEL_ID, "Chiaki audio", NotificationManager.IMPORTANCE_LOW)
			channel.setShowBadge(false)
			nm.createNotificationChannel(channel)
		}
	}

	private fun buildNotification(): Notification
	{
		val immutable = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

		val openIntent = Intent(this, StreamActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
		}
		val openPending = PendingIntent.getActivity(this, 0, openIntent, immutable or PendingIntent.FLAG_UPDATE_CURRENT)

		val stopIntent = Intent(this, AudioBackgroundService::class.java).apply { action = ACTION_STOP }
		val stopPending = PendingIntent.getService(this, 1, stopIntent, immutable or PendingIntent.FLAG_UPDATE_CURRENT)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.ic_media_play)
			.setContentTitle("Chiaki")
			.setContentText("Reproduciendo audio de la consola")
			.setContentIntent(openPending)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
			.build()
	}
}

/** Registro simple de diagnostico: guarda pasos y errores en un archivo para mostrarlos en el proximo arranque. */
object DebugTrace
{
	private fun file(context: Context) = File(context.filesDir, "trace.txt")

	@Synchronized
	fun add(context: Context, msg: String)
	{
		try
		{
			Log.i("ChiakiTrace", msg)
			file(context).appendText("${System.currentTimeMillis() % 100000000} $msg\n")
		}
		catch(e: Throwable) {}
	}

	fun addError(context: Context, where: String, e: Throwable)
	{
		add(context, "ERROR en $where: ${Log.getStackTraceString(e).take(1500)}")
	}

	@Synchronized
	fun takeAndClear(context: Context): String?
	{
		return try
		{
			val f = file(context)
			if(!f.exists())
				return null
			val text = f.readLines().takeLast(40).joinToString("\n")
			f.delete()
			text
		}
		catch(e: Throwable) { null }
	}
}
