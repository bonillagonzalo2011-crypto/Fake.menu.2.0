package com.example.freezer

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuShell {
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** Shizuku está abierto y activado. */
    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    /** Shizuku fue iniciado con root (puede usar iptables). */
    fun isRoot(): Boolean = try { isRunning() && Shizuku.getUid() == 0 } catch (_: Throwable) { false }

    /** Shizuku está activo y esta app ya tiene permiso. */
    fun hasPermission(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission(code: Int) = Shizuku.requestPermission(code)

    /** Corre un comando con los permisos de Shizuku. Llamar fuera del hilo principal. */
    fun run(cmd: String) {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            (m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process).waitFor()
        } catch (_: Throwable) {
        }
    }
}
