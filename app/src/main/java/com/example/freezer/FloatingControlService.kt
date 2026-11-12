package com.example.freezer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Botón flotante (Freezer).
 * - Un dedo: lo arrastra. Un toque: corta la red.
 * - La red vuelve sola cuando la cuenta regresiva llega a 0, o antes si tocás el botón de nuevo.
 * - Dos dedos (pellizco) o mantener apretado y deslizar arriba/abajo: cambia el tamaño.
 * - Suena "pi, pi, pi" durante la cuenta y una voz dice "Función activada".
 * - Modo Shizuku: corta la red con Shizuku en lugar de la VPN.
 */
class FloatingControlService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val PREFS = "freezer_prefs"
        const val KEY_SCALE = "button_scale"
        const val KEY_ROOT = "root_mode"
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.5f
        private const val BASE_SIZE_DP = 52
        private const val BASE_PADDING_DP = 6

        private const val FREEZE_DURATION_MS = 3000L
        private const val COUNTDOWN_SPEED = 1.5f
        private const val TICK_MS = 50L
        private const val BEEP_INTERVAL_MS = 500L

        // Con Shizuku sin root: apaga WiFi y datos.
        private const val ROOT_FREEZE_CMD = "svc wifi disable; svc data disable"
        private const val ROOT_UNFREEZE_CMD = "svc wifi enable; svc data enable"

        // Con Shizuku con root: corta con iptables.
        private const val IPTABLES_FREEZE_CMD =
            "iptables -I OUTPUT 1 -j DROP; iptables -I INPUT 1 -j DROP; " +
            "ip6tables -I OUTPUT 1 -j DROP; ip6tables -I INPUT 1 -j DROP"
        private const val IPTABLES_UNFREEZE_CMD =
            "iptables -D OUTPUT -j DROP; iptables -D INPUT -j DROP; " +
            "ip6tables -D OUTPUT -j DROP; ip6tables -D INPUT -j DROP"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var scale = 1f

    private val handler = Handler(Looper.getMainLooper())
    private var button: ImageButton? = null
    private var countdownText: TextView? = null
    private var countdownEnd = 0L

    // Estado de corte cuando se usa el modo Shizuku.
    private var rootFrozen = false

    private var tts: TextToSpeech? = null
    private var lastBeep = 0L

    // Foto del gato en círculo para el botón.
    private val cat by lazy {
        val bmp = BitmapFactory.decodeResource(resources, R.drawable.foto_perfil)
        RoundedBitmapDrawableFactory.create(resources, bmp).apply { isCircular = true }
    }

    private fun makeTone(freq: Double): AudioTrack {
        val rate = 44100
        val n = (rate * 0.09).toInt()
        val buf = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = min(1.0, t / 0.004) * exp(-t * 55.0)
            val s = sin(2 * PI * freq * t) * env
            buf[i] = (s * 0.95 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, n)
        return track
    }

    private val tickTrack by lazy { makeTone(2200.0) }
    private val endTrack by lazy { makeTone(2800.0) }

    private fun play(track: AudioTrack) {
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    private fun beep() = play(tickTrack)
    private fun beepEnd() = play(endTrack)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_SCALE) {
            val newScale = prefs.getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
            if (abs(newScale - scale) > 0.001f) resizeTo(newScale)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rootMode() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ROOT, false)

    private fun isFrozenNow() = if (rootMode()) rootFrozen else MyVpnService.isFrozen

    private fun runRoot(cmd: String) {
        Thread {
            ShizukuShell.run(cmd)
        }.start()
    }

    private fun rootFreeze() {
        rootFrozen = true
        runRoot(if (ShizukuShell.isRoot()) IPTABLES_FREEZE_CMD else ROOT_FREEZE_CMD)
    }

    private fun rootUnfreeze() {
        rootFrozen = false
        runRoot(if (ShizukuShell.isRoot()) IPTABLES_UNFREEZE_CMD else ROOT_UNFREEZE_CMD)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("es", "ES"))
                tts?.setPitch(1.3f) // más agudo, suena más de mujer
            }
        }
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scale = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        setupButton()
        windowManager?.addView(bubbleView, params)

        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)

        // La VPN queda siempre prendida (sin cortar nada). En modo Shizuku no se usa.
        if (!rootMode()) sendVpn(MyVpnService.ACTION_START)
    }

    private fun sendVpn(action: String) {
        startService(Intent(this, MyVpnService::class.java).apply { this.action = action })
    }

    private fun currentSizePx() = (dp(BASE_SIZE_DP) * scale).toInt()

    private fun applySize(btn: ImageButton) {
        val size = currentSizePx()
        val pad = (dp(BASE_PADDING_DP) * scale).toInt()
        btn.layoutParams = FrameLayout.LayoutParams(size, size)
        btn.setPadding(pad, pad, pad, pad)
        countdownText?.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.30f)
    }

    private fun setupButton() {
        val btn = bubbleView?.findViewById<ImageButton>(R.id.btnFreezer) ?: return
        button = btn
        countdownText = bubbleView?.findViewById(R.id.tvCountdown)
        btn.setImageDrawable(cat)
        applySize(btn)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isMoving = false
        var wasScaling = false
        var resizeMode = false
        var resizeStartScale = 1f
        val slop = dp(8)

        // Mantener apretado: entra al modo "cambiar tamaño".
        val longPress = Runnable {
            if (!isMoving && !wasScaling) {
                resizeMode = true
                resizeStartScale = scale
                btn.alpha = 0.7f
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldSize = currentSizePx()
                    scale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    applySize(btn)
                    val diff = (currentSizePx() - oldSize) / 2
                    params.x -= diff
                    params.y -= diff
                    windowManager?.updateViewLayout(bubbleView, params)
                    return true
                }
            }
        )

        btn.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isMoving = false
                    wasScaling = false
                    resizeMode = false
                    handler.postDelayed(longPress, 500)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    handler.removeCallbacks(longPress)
                    wasScaling = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (wasScaling || event.pointerCount > 1) return@setOnTouchListener true
                    if (resizeMode) {
                        // Arriba agranda, abajo achica.
                        val newScale = (resizeStartScale + (touchY - event.rawY) / dp(150))
                            .coerceIn(MIN_SCALE, MAX_SCALE)
                        resizeTo(newScale)
                        return@setOnTouchListener true
                    }
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        isMoving = true
                        handler.removeCallbacks(longPress)
                    }
                    if (isMoving) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (resizeMode) {
                        resizeMode = false
                        btn.alpha = 1f
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putFloat(KEY_SCALE, scale).apply()
                    } else if (wasScaling) {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putFloat(KEY_SCALE, scale).apply()
                    } else if (!isMoving) {
                        v.performClick()
                        toggleFreezer()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    resizeMode = false
                    btn.alpha = 1f
                    true
                }
                else -> false
            }
        }
    }

    private fun resizeTo(newScale: Float) {
        val btn = button ?: return
        val oldSize = currentSizePx()
        scale = newScale
        applySize(btn)
        val diff = (currentSizePx() - oldSize) / 2
        params.x -= diff
        params.y -= diff
        windowManager?.updateViewLayout(bubbleView, params)
    }

    private fun toggleFreezer() {
        if (isFrozenNow()) beepEnd()
        if (rootMode()) {
            if (rootFrozen) rootUnfreeze() else rootFreeze()
        } else {
            sendVpn(MyVpnService.ACTION_TOGGLE_FREEZER)
        }
        handler.postDelayed({ refreshUi() }, 150)
    }

    private fun refreshUi() {
        if (isFrozenNow()) showFrozen() else showNormal()
    }

    /** Red cortada: número con la cuenta regresiva en lugar de la foto. */
    private fun showFrozen() {
        val btn = button ?: return
        btn.setImageDrawable(null)
        btn.setBackgroundResource(R.drawable.bg_freezer_frozen)
        countdownText?.visibility = View.VISIBLE
        countdownEnd = SystemClock.elapsedRealtime() + (FREEZE_DURATION_MS / COUNTDOWN_SPEED).toLong()
        handler.removeCallbacks(tick)
        handler.post(tick)
        lastBeep = SystemClock.elapsedRealtime()
        beep()
        tts?.speak("Función activada", TextToSpeech.QUEUE_FLUSH, null, "freezer_on")
    }

    /** Red normal: foto del gato. */
    private fun showNormal() {
        val btn = button ?: return
        handler.removeCallbacks(tick)
        countdownText?.visibility = View.GONE
        btn.setBackgroundResource(R.drawable.bg_freezer_normal)
        btn.clearColorFilter()
        btn.setImageDrawable(cat)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isFrozenNow()) {
                showNormal()
                return
            }
            val remaining = countdownEnd - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                beepEnd()
                if (rootMode()) rootUnfreeze() else sendVpn(MyVpnService.ACTION_UNFREEZE)
                handler.postDelayed({ refreshUi() }, 150)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBeep >= BEEP_INTERVAL_MS) {
                lastBeep = now
                beep()
            }
            countdownText?.text = String.format(Locale.US, "%.1f", remaining * COUNTDOWN_SPEED / 1000.0)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startAsForeground() {
        val channelId = "floating_control"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Botón flotante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Botón Freezer activo")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        runCatching { tickTrack.release(); endTrack.release() }
        handler.removeCallbacksAndMessages(null)
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        // La VPN queda prendida; solo se vuelve a conectar la red.
        if (rootMode()) {
            if (rootFrozen) rootUnfreeze()
        } else {
            sendVpn(MyVpnService.ACTION_UNFREEZE)
        }
        bubbleView?.let { windowManager?.removeView(it) }
        isRunning = false
        super.onDestroy()
    }
}
