package com.example.freezer

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * VPN local que queda siempre prendida.
 * - Normal: la VPN está conectada pero no desvía nada, la red anda como siempre.
 * - Cortada: desvía el tráfico hacia la VPN y lo descarta. Si elegiste apps puntuales,
 *   solo esas pierden internet; si no, todas.
 */
class MyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.freezer.START"
        const val ACTION_STOP = "com.example.freezer.STOP"
        const val ACTION_TOGGLE_FREEZER = "com.example.freezer.TOGGLE_FREEZER"
        const val ACTION_UNFREEZE = "com.example.freezer.UNFREEZE"

        const val KEY_CUT_ALL = "cut_all"
        const val KEY_CUT_PKGS = "cut_pkgs"

        @Volatile
        var isFrozen = false
            private set

        @Volatile
        var onStateChanged: ((Boolean) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> setFrozen(false)
            ACTION_TOGGLE_FREEZER -> setFrozen(!isFrozen)
            ACTION_UNFREEZE -> setFrozen(false)
            ACTION_STOP -> {
                closeTun()
                isFrozen = false
                onStateChanged?.invoke(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setFrozen(frozen: Boolean) {
        closeTun()

        val prefs = getSharedPreferences(FloatingControlService.PREFS, MODE_PRIVATE)
        val cutAll = prefs.getBoolean(KEY_CUT_ALL, true)
        val pkgs = prefs.getStringSet(KEY_CUT_PKGS, emptySet()) ?: emptySet()

        val builder = Builder()
            .setSession("FAKE 2.0")
            .addAddress("10.0.0.2", 32)
            .setBlocking(true)

        if (frozen && !cutAll) {
            // Solo las apps elegidas pasan por la VPN (y pierden internet).
            var added = 0
            for (p in pkgs) {
                try {
                    builder.addAllowedApplication(p)
                    added++
                } catch (_: Exception) {
                }
            }
            // Si no quedó ninguna, no se corta nada.
            if (added == 0) builder.addAllowedApplication(packageName)
        } else {
            builder.addDisallowedApplication(packageName)
        }

        if (frozen) {
            builder.addAddress("fd00::2", 128)
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            // Ruta que no se usa: la VPN queda conectada sin desviar tráfico.
            builder.addRoute("10.255.255.255", 32)
        }

        val pfd = try { builder.establish() } catch (_: Exception) { null }
        tun = pfd
        isFrozen = frozen && pfd != null
        if (isFrozen && pfd != null) startDrain(pfd)
        onStateChanged?.invoke(isFrozen)
    }

    /** Lee y descarta todo lo que llega a la VPN, así la red queda cortada. */
    private fun startDrain(pfd: ParcelFileDescriptor) {
        Thread {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (input.read(buf) >= 0) {
                    // se descarta
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onRevoke() {
        closeTun()
        isFrozen = false
        onStateChanged?.invoke(false)
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }
}
