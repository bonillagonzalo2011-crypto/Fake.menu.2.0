package com.example.freezer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var switchFreezer: SwitchCompat
    private lateinit var ivFreezer: ImageView
    private lateinit var seekSize: SeekBar
    private lateinit var tvSizeValue: TextView
    private lateinit var switchRoot: SwitchCompat
    private lateinit var tvRootStatus: TextView
    private lateinit var tvCutSub: TextView
    private lateinit var llOpen: LinearLayout

    private val appPassword = "N9"

    // Free Fire y Free Fire MAX (paquete -> nombre por si no está instalado).
    private val ffNames = mapOf(
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX"
    )
    private val allowedPkgs: Set<String> = ffNames.keys

    private val sizePrefs by lazy {
        getSharedPreferences(FloatingControlService.PREFS, MODE_PRIVATE)
    }

    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FloatingControlService.KEY_SCALE) showSize(currentScale())
    }
    private var askedNotif = false
    private var askedOverlay = false
    private var askedVpn = false

    private val shizukuCode = 4242
    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != shizukuCode) return@OnRequestPermissionResultListener
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    saveRootMode(true)
                } else {
                    switchRoot.isChecked = false
                    tvRootStatus.text = "Permiso de Shizuku denegado"
                }
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextStep() }
    private val overlaySettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextStep() }
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(shizukuListener)

        switchFreezer = findViewById(R.id.switchFreezer)
        ivFreezer = findViewById(R.id.ivFreezer)
        setupSizeSlider()
        setupRootSwitch()
        setupCutApps()
        switchFreezer.isChecked = FloatingControlService.isRunning
        updateFreezerIcon(MyVpnService.isFrozen)

        switchFreezer.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                askedNotif = false
                askedOverlay = false
                askedVpn = false
                nextStep()
            } else {
                stopService(Intent(this, FloatingControlService::class.java))
            }
        }

        askPassword()
    }

    /** Portada: fondo animado y cuadro de contraseña. Hasta que sea correcta, el menú queda tapado. */
    private fun askPassword() {
        val bg = NetworkBackgroundView(this)
        addContentView(
            bg,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val d = resources.displayMetrics.density

        val brand = TextView(this).apply {
            text = "FAKE 2.0"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * d).toInt())
        }

        val avatar = ImageView(this).apply {
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.foto_perfil)
            val round = RoundedBitmapDrawableFactory.create(resources, bmp)
            round.isCircular = true
            setImageDrawable(round)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
            setTypeface(null, Typeface.ITALIC)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (24 * d).toInt()
            setPadding(m, m / 2, m, 0)
            addView(brand)
            addView(avatar, LinearLayout.LayoutParams((80 * d).toInt(), (80 * d).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * d).toInt()
            })
            addView(input)
        }

        val title = SpannableString("Password").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, 0)
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .setNegativeButton("Salir") { _, _ -> finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == appPassword) {
                    (bg.parent as? ViewGroup)?.removeView(bg)
                    dialog.dismiss()
                } else {
                    input.error = "Password incorrecto"
                    input.text.clear()
                }
            }
        }

        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C"))
                cornerRadius = 20 * d
            }
        )
        dialog.show()
    }

    // ---------------------------------------------------------------------
    // Apps que se cortan + Injetar fake (abrir Free Fire)
    // ---------------------------------------------------------------------

    private class AppItem(val label: String, val pkg: String, val icon: Drawable)

    /** Free Fire y Free Fire MAX instalados, con su ícono. */
    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg in allowedPkgs }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun setupCutApps() {
        tvCutSub = findViewById(R.id.tvCutSub)
        llOpen = findViewById(R.id.llOpen)
        findViewById<View>(R.id.cardCut).setOnClickListener { showCutPicker() }
        refreshCut()
    }

    private fun refreshCut() {
        val all = sizePrefs.getBoolean(MyVpnService.KEY_CUT_ALL, true)
        val pkgs = sizePrefs.getStringSet(MyVpnService.KEY_CUT_PKGS, emptySet()) ?: emptySet()
        tvCutSub.text = when {
            all -> "Todas las apps"
            pkgs.isEmpty() -> "Ninguna app elegida"
            pkgs.size == 1 -> "1 app elegida"
            else -> "${pkgs.size} apps elegidas"
        }
        renderOpenBox()
    }

    /** Lista con el ícono de cada app y una casilla; arriba, "Todas las apps". */
    private fun showCutPicker() {
        val apps = loadApps()
        var all = sizePrefs.getBoolean(MyVpnService.KEY_CUT_ALL, true)
        val chosen = (sizePrefs.getStringSet(MyVpnService.KEY_CUT_PKGS, emptySet()) ?: emptySet())
            .toMutableSet()

        val list = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size + 1
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val icon = v.findViewById<ImageView>(R.id.ivAppIcon)
                val name = v.findViewById<TextView>(R.id.tvAppLabel)
                val check = v.findViewById<CheckBox>(R.id.cbApp)
                if (position == 0) {
                    icon.setImageResource(android.R.drawable.btn_star_big_on)
                    name.text = "Todas las apps"
                    check.isChecked = all
                } else {
                    val app = apps[position - 1]
                    icon.setImageDrawable(app.icon)
                    name.text = app.label
                    check.isChecked = !all && app.pkg in chosen
                }
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                all = !all
            } else {
                all = false
                val pkg = apps[position - 1].pkg
                if (!chosen.add(pkg)) chosen.remove(pkg)
            }
            adapter.notifyDataSetChanged()
        }

        AlertDialog.Builder(this)
            .setTitle("Apps que se cortan")
            .setView(list)
            .setPositiveButton("Listo") { _, _ ->
                sizePrefs.edit()
                    .putBoolean(MyVpnService.KEY_CUT_ALL, all)
                    .putStringSet(MyVpnService.KEY_CUT_PKGS, chosen)
                    .apply()
                refreshCut()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Cuadro "Injetar fake": Free Fire y Free Fire MAX siempre visibles, cada uno con su botón Abrir. */
    private fun renderOpenBox() {
        llOpen.removeAllViews()

        val pm = packageManager
        val d = resources.displayMetrics.density
        val blue = ColorStateList.valueOf(Color.parseColor("#2979FF"))

        val items = ffNames.map { (pkg, fallback) ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                fallback
            }
            val icon = try {
                pm.getApplicationIcon(pkg)
            } catch (_: Exception) {
                ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)!!
            }
            Triple(pkg, label, icon)
        }

        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 16 * d
                setStroke(maxOf(1, d.toInt()), Color.parseColor("#33FFFFFF"))
            }
            clipToOutline = true
        }
        frame.addView(
            NetworkBackgroundView(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
        }
        card.addView(TextView(this).apply {
            text = "Injetar fake"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        card.addView(TextView(this).apply {
            text = "Toque em Abrir para entrar"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 13f
        })
        for ((pkg, name, icon) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * d).toInt(), 0, 0)
            }
            row.addView(
                ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
            )
            row.addView(
                TextView(this).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding((16 * d).toInt(), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Button(this).apply {
                text = "Abrir"
                setTextColor(Color.WHITE)
                backgroundTintList = blue
                setOnClickListener { openApp(pkg) }
            })
            card.addView(row)
        }
        frame.addView(card)
        llOpen.addView(
            frame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * d).toInt() }
        )
    }

    private fun openApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "No está instalada", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        updateFreezerIcon(MyVpnService.isFrozen)
        MyVpnService.onStateChanged = { frozen -> runOnUiThread { updateFreezerIcon(frozen) } }
        showSize(currentScale())
        sizePrefs.registerOnSharedPreferenceChangeListener(sizeListener)
    }

    override fun onStop() {
        MyVpnService.onStateChanged = null
        sizePrefs.unregisterOnSharedPreferenceChangeListener(sizeListener)
        super.onStop()
    }

    private fun currentScale() = sizePrefs
        .getFloat(FloatingControlService.KEY_SCALE, 1f)
        .coerceIn(FloatingControlService.MIN_SCALE, FloatingControlService.MAX_SCALE)

    private fun showSize(scale: Float) {
        val pct = Math.round(scale * 100)
        seekSize.progress = pct - 60
        tvSizeValue.text = "$pct%"
    }

    private fun setupSizeSlider() {
        seekSize = findViewById(R.id.seekSize)
        tvSizeValue = findViewById(R.id.tvSizeValue)
        seekSize.max = 190
        showSize(currentScale())

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val pct = progress + 60
                tvSizeValue.text = "$pct%"
                if (fromUser) sizePrefs.edit().putFloat(FloatingControlService.KEY_SCALE, pct / 100f).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun isRootMode() = sizePrefs.getBoolean(FloatingControlService.KEY_ROOT, false)

    private fun setupRootSwitch() {
        switchRoot = findViewById(R.id.switchRoot)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        switchRoot.isChecked = isRootMode()
        showRootStatus()

        switchRoot.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener

            if (!checked) {
                saveRootMode(false)
                return@setOnCheckedChangeListener
            }

            when {
                !ShizukuShell.isRunning() -> {
                    switchRoot.isChecked = false
                    tvRootStatus.text = "Shizuku no está activo: abrilo y activalo, después probá de nuevo"
                    packageManager.getLaunchIntentForPackage(ShizukuShell.PACKAGE)
                        ?.let { startActivity(it) }
                }
                ShizukuShell.hasPermission() -> saveRootMode(true)
                else -> {
                    tvRootStatus.text = "Pidiendo permiso a Shizuku..."
                    ShizukuShell.requestPermission(shizukuCode)
                }
            }
        }
    }

    private fun saveRootMode(on: Boolean) {
        sizePrefs.edit().putBoolean(FloatingControlService.KEY_ROOT, on).apply()
        stopService(Intent(this, FloatingControlService::class.java))
        switchFreezer.isChecked = false
        switchRoot.isChecked = on
        showRootStatus()
    }

    private fun showRootStatus() {
        tvRootStatus.text = if (isRootMode()) {
            "Shizuku activo: corta WiFi y datos, sin VPN"
        } else {
            "Desactivado: se usa la VPN"
        }
    }

    private fun updateFreezerIcon(frozen: Boolean) {
        if (frozen) {
            ivFreezer.setImageResource(android.R.drawable.ic_media_play)
            ivFreezer.setColorFilter(Color.parseColor("#2979FF"))
        } else {
            ivFreezer.setImageResource(android.R.drawable.ic_media_pause)
            ivFreezer.setColorFilter(Color.parseColor("#FF5252"))
        }
    }

    private fun nextStep() {
        if (!switchFreezer.isChecked) return

        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            if (askedOverlay) { switchFreezer.isChecked = false; return }
            askedOverlay = true
            overlaySettings.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // En modo Shizuku no se usa la VPN, así que no hace falta su permiso.
        val vpnIntent = if (isRootMode()) null else VpnService.prepare(this)
        if (vpnIntent != null) {
            if (askedVpn) { switchFreezer.isChecked = false; return }
            askedVpn = true
            vpnPermission.launch(vpnIntent)
            return
        }

        startForegroundService(Intent(this, FloatingControlService::class.java))
    }
}
