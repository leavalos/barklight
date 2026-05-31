package com.huesync.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.huesync.tv.capture.CaptureService
import com.huesync.tv.capture.UsbCaptureService
import com.huesync.tv.hue.HueBridge
import com.huesync.tv.model.Config
import com.huesync.tv.model.CaptureMode
import com.huesync.tv.model.LightZone
import com.huesync.tv.model.Zone

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PROJECTION = 1001
    }

    private lateinit var statusText: TextView
    private lateinit var modeText: TextView
    private lateinit var bridgeIpInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var tokenRow: LinearLayout
    private lateinit var btnPair: Button
    private lateinit var btnStartScreen: Button
    private lateinit var btnStartUsb: Button
    private lateinit var btnStop: Button
    private lateinit var btnVerify: Button
    private lateinit var lightsContainer: LinearLayout
    private lateinit var pairCountdown: TextView

    private var config = Config()
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText      = findViewById(R.id.statusText)
        modeText        = findViewById(R.id.modeText)
        bridgeIpInput   = findViewById(R.id.bridgeIpInput)
        tokenInput      = findViewById(R.id.tokenInput)
        tokenRow        = findViewById(R.id.tokenRow)
        btnPair         = findViewById(R.id.btnPair)
        btnStartScreen  = findViewById(R.id.btnStartScreen)
        btnStartUsb     = findViewById(R.id.btnStartUsb)
        btnStop         = findViewById(R.id.btnStop)
        btnVerify       = findViewById(R.id.btnVerify)
        lightsContainer = findViewById(R.id.lightsContainer)
        pairCountdown   = findViewById(R.id.pairCountdown)

        config = Config.load(this)
        bridgeIpInput.setText(config.bridgeIp)
        tokenInput.setText(config.bridgeToken)

        // Mostrar token solo si ya hay uno guardado
        tokenRow.visibility = if (config.bridgeToken.isNotEmpty()) View.VISIBLE else View.GONE

        renderLights()

        btnPair.setOnClickListener        { startPairing() }
        btnVerify.setOnClickListener      { verifyBridge() }
        btnStartScreen.setOnClickListener { startScreenCapture() }
        btnStartUsb.setOnClickListener    { startUsbCapture() }
        btnStop.setOnClickListener        { stopAll() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── PAIRING AUTOMÁTICO ────────────────────────────────────────────────────

    private fun startPairing() {
        val ip = bridgeIpInput.text.toString().trim()
        if (ip.isEmpty()) {
            setStatus("Ingresa la IP del Bridge primero")
            return
        }

        // Guardar IP
        config = config.copy(bridgeIp = ip)
        Config.save(this, config)

        // Mostrar cuenta regresiva de 30 segundos
        setStatus("Presiona el boton fisico del Bridge ahora...")
        pairCountdown.visibility = View.VISIBLE
        btnPair.isEnabled = false

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(30_000, 1000) {
            override fun onTick(ms: Long) {
                pairCountdown.text = "Solicitando token en ${ms / 1000}s..."
                // Intentar obtener token en cada tick (el Bridge acepta hasta 30s)
                if (ms < 28_000) return  // esperar 2s antes del primer intento
                tryGetToken(ip)
            }
            override fun onFinish() {
                pairCountdown.visibility = View.GONE
                btnPair.isEnabled = true
                if (config.bridgeToken.isEmpty()) {
                    setStatus("Tiempo agotado. Presiona el boton del Bridge e intenta de nuevo.")
                }
            }
        }.start()

        // Intentar inmediatamente también
        tryGetToken(ip)
    }

    private var tokenObtained = false

    private fun tryGetToken(ip: String) {
        if (tokenObtained) return
        HueBridge.requestToken(ip) { token, error ->
            runOnUiThread {
                if (token != null && !tokenObtained) {
                    tokenObtained = true
                    countdownTimer?.cancel()
                    pairCountdown.visibility = View.GONE
                    btnPair.isEnabled = true

                    // Guardar token y mostrarlo
                    config = config.copy(bridgeToken = token)
                    Config.save(this, config)
                    tokenInput.setText(token)
                    tokenRow.visibility = View.VISIBLE

                    setStatus("Conectado al Bridge correctamente")
                    verifyBridge()  // verificar automáticamente
                } else if (error != null && error != "Presiona el botón del Bridge primero") {
                    // Error real (no es "aún no presionaste el botón")
                    countdownTimer?.cancel()
                    pairCountdown.visibility = View.GONE
                    btnPair.isEnabled = true
                    setStatus("Error: $error")
                }
                // Si el error es "presiona el botón" seguimos esperando
            }
        }
    }

    // ── VERIFICAR BRIDGE ──────────────────────────────────────────────────────

    private fun verifyBridge() {
        val ip    = bridgeIpInput.text.toString().trim()
        val token = tokenInput.text.toString().trim().ifEmpty { config.bridgeToken }
        if (ip.isEmpty() || token.isEmpty()) {
            setStatus("Vincula el Bridge primero")
            return
        }
        saveConfig(config.captureMode)
        setStatus("Verificando Bridge...")
        HueBridge(ip, token).verifyConnection { ok, lights ->
            runOnUiThread {
                if (ok) setStatus("Bridge OK  Luces disponibles: ${lights.joinToString(", ")}")
                else    setStatus("No se pudo conectar al Bridge")
            }
        }
    }

    // ── CAPTURA ───────────────────────────────────────────────────────────────

    private fun startScreenCapture() {
        saveConfig(CaptureMode.SCREEN)
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.e(TAG, "onActivityResult: req=$requestCode result=$resultCode data=$data")
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.e(TAG, "Permiso concedido — iniciando CaptureService")
                try {
                    val intent = Intent(this, CaptureService::class.java).apply {
                        putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    }
                    startForegroundService(intent)
                    Log.e(TAG, "startForegroundService OK")
                    setStatus("Capturando pantalla")
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando servicio: ${e.message}", e)
                    setStatus("Error: ${e.message}")
                }
            } else {
                Log.e(TAG, "Permiso denegado resultCode=$resultCode")
                setStatus("Permiso de captura denegado")
            }
            updateUI()
        }
    }

    private fun startUsbCapture() {
        saveConfig(CaptureMode.USB)
        startForegroundService(Intent(this, UsbCaptureService::class.java))
        setStatus("Capturando desde USB")
        updateUI()
    }

    private fun stopAll() {
        startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
        startService(Intent(this, UsbCaptureService::class.java).apply { action = UsbCaptureService.ACTION_STOP })
        setStatus("Detenido")
        updateUI()
    }

    // ── LUCES ─────────────────────────────────────────────────────────────────

    private fun renderLights() {
        lightsContainer.removeAllViews()
        config.lights.forEachIndexed { idx, light ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val idInput = EditText(this).apply {
                hint = "ID"
                setText(light.lightId)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            val zoneSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity,
                        android.R.layout.simple_spinner_item, Zone.values().map { it.name }).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(Zone.values().indexOf(light.zone))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = Button(this).apply {
                text = "X"
                setOnClickListener {
                    val newLights = config.lights.toMutableList().also { it.removeAt(idx) }
                    config = config.copy(lights = newLights)
                    renderLights()
                }
            }

            row.addView(idInput)
            row.addView(zoneSpinner)
            row.addView(removeBtn)
            lightsContainer.addView(row)
        }

        val addBtn = Button(this).apply {
            text = "Agregar luz"
            setOnClickListener {
                val newLights = config.lights.toMutableList()
                        .also { it.add(LightZone("${it.size + 1}", Zone.LEFT)) }
                config = config.copy(lights = newLights)
                renderLights()
            }
        }
        lightsContainer.addView(addBtn)
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun saveConfig(mode: CaptureMode) {
        val lights = mutableListOf<LightZone>()
        for (i in 0 until lightsContainer.childCount - 1) {
            val row = lightsContainer.getChildAt(i) as? LinearLayout ?: continue
            val idInput     = row.getChildAt(0) as? EditText ?: continue
            val zoneSpinner = row.getChildAt(1) as? Spinner ?: continue
            lights.add(LightZone(
                    lightId = idInput.text.toString().trim().ifEmpty { "${i + 1}" },
                    zone    = Zone.valueOf(zoneSpinner.selectedItem.toString())
            ))
        }
        config = config.copy(
                bridgeIp    = bridgeIpInput.text.toString().trim(),
                bridgeToken = tokenInput.text.toString().trim().ifEmpty { config.bridgeToken },
                captureMode = mode,
                lights      = lights.ifEmpty { config.lights }
        )
        Config.save(this, config)
    }

    private fun updateUI() {
        val screenRunning = CaptureService.isRunning
        val usbRunning    = UsbCaptureService.isRunning
        val anyRunning    = screenRunning || usbRunning

        btnStartScreen.isEnabled = !anyRunning
        btnStartUsb.isEnabled    = !anyRunning
        btnStop.isEnabled        = anyRunning

        modeText.text = when {
            screenRunning -> "Modo: captura de pantalla"
            usbRunning    -> "Modo: capturadora USB"
            else          -> "Modo: inactivo"
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
        Log.d(TAG, msg)
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!CaptureService.isRunning && !UsbCaptureService.isRunning) startScreenCapture()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopAll(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}