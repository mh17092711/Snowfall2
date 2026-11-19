package com.example.snowfall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rowParams(top: Int = 8): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SnowConfig.load(this)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(32), pad, pad)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
        setContentView(scroll)

        // Sarlavha
        root.addView(TextView(this).apply {
            text = "Snowfall"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        // Holat va asosiy tugma
        statusText = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        root.addView(statusText, rowParams(16))

        toggleButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { onToggleClicked() }
        }
        root.addView(toggleButton, rowParams(12))

        // Ob-havo rejimi
        addHeader(root, "Ob-havo")
        val modeNames = arrayOf("❄ Qor", "🌧 Yomg'ir", "🍂 Kuz", "⛈ Momaqaldiroq")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (i in modeNames.indices) {
            group.addView(RadioButton(this).apply {
                id = 100 + i
                text = modeNames[i]
                textSize = 16f
            })
        }
        group.check(100 + SnowConfig.mode)
        group.setOnCheckedChangeListener { _, checkedId ->
            SnowConfig.mode = checkedId - 100
            SnowConfig.save(this)
        }
        root.addView(group, rowParams(4))

        root.addView(TextView(this).apply {
            text = "Qor uyumi va qorbobo faqat \"Qor\" rejimida ishlaydi."
            textSize = 13f
            setTextColor(Color.GRAY)
        }, rowParams(4))

        root.addView(CheckBox(this).apply {
            text = "Qor uyumi va qorbobo"
            textSize = 16f
            isChecked = SnowConfig.pile
            setOnCheckedChangeListener { _, checked ->
                SnowConfig.pile = checked
                SnowConfig.save(this@MainActivity)
            }
        }, rowParams(8))

        root.addView(CheckBox(this).apply {
            text = "Bulutlar"
            textSize = 16f
            isChecked = SnowConfig.clouds
            setOnCheckedChangeListener { _, checked ->
                SnowConfig.clouds = checked
                SnowConfig.save(this@MainActivity)
            }
        }, rowParams(4))

        // Sozlamalar
        addHeader(root, "Sozlamalar")
        addSlider(root, "O'lcham", 50, 250, "%", { SnowConfig.sizePct }, { SnowConfig.sizePct = it })
        addSlider(root, "Miqdor", 10, 300, "%", { SnowConfig.countPct }, { SnowConfig.countPct = it })
        addSlider(root, "Tushish tezligi", 40, 250, "%", { SnowConfig.speedPct }, { SnowConfig.speedPct = it })
        addSlider(root, "Shamol (minus: chapga, plyus: o'ngga)", -100, 100, "", { SnowConfig.windPct }, { SnowConfig.windPct = it })

        // Qor rangi
        addHeader(root, "Qor rangi")
        val colorNames = arrayOf("Oq", "Ko'k", "Pushti", "Oltin", "Rang-barang")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colorNames)
        spinner.setSelection(SnowConfig.colorIdx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SnowConfig.colorIdx = position
                SnowConfig.save(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner, rowParams(4))

        // Standart holatga qaytarish
        root.addView(Button(this).apply {
            text = "Standart sozlamalar"
            setOnClickListener {
                SnowConfig.reset()
                SnowConfig.save(this@MainActivity)
                recreate()
            }
        }, rowParams(24))

        requestNotificationPermissionIfNeeded()

        // Ilova ochilganda ruxsatni tekshirish
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
        }
    }

    private fun addHeader(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, rowParams(24))
    }

    private fun addSlider(
        parent: LinearLayout,
        title: String,
        lo: Int,
        hi: Int,
        unit: String,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val label = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            text = "$title: ${get()}$unit"
        }
        val seek = SeekBar(this)
        seek.max = hi - lo
        seek.progress = get() - lo
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + lo
                set(v)
                label.text = "$title: $v$unit"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                SnowConfig.save(this@MainActivity)
            }
        })
        parent.addView(label, rowParams(12))
        parent.addView(seek, rowParams(0))
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun onToggleClicked() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Avval \"Boshqa ilovalar ustida ko'rsatish\" ruxsatini bering",
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings()
            return
        }

        if (SnowfallService.isRunning) {
            stopService(Intent(this, SnowfallService::class.java))
            SnowfallService.isRunning = false
        } else {
            val intent = Intent(this, SnowfallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            SnowfallService.isRunning = true
        }
        updateUi()
    }

    private fun updateUi() {
        val granted = Settings.canDrawOverlays(this)
        when {
            !granted -> {
                statusText.text = "Ruxsat berilmagan.\nTugmani bosib sozlamalarni oching."
                toggleButton.text = "Ruxsat berish"
            }
            SnowfallService.isRunning -> {
                statusText.text = "Ishlayapti ✨"
                toggleButton.text = "O'chirish"
            }
            else -> {
                statusText.text = "O'chiq"
                toggleButton.text = "Yoqish"
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
