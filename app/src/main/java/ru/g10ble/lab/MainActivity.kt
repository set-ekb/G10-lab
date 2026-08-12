package ru.g10ble.lab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * G10 BLE Lab v0.2
 *
 * Safe/read-only protocol-analysis prototype for YADEA G10 / BEKEN BK-BLE-1.0.
 *
 * Important: this version NEVER writes to FFF1 and NEVER touches OTA/OAD services.
 * The only GATT descriptor write performed is the standard CCCD write used to
 * subscribe to notifications on FFF2.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_ENABLE_BT = 1002
        private const val REQ_EXPORT_CSV = 1003

        private val UUID_GATT_FFF0 = uuid16("FFF0")
        private val UUID_GATT_FFF1 = uuid16("FFF1")
        private val UUID_GATT_FFF2 = uuid16("FFF2")

        private val UUID_DEVICE_INFO = uuid16("180A")
        private val UUID_MANUFACTURER = uuid16("2A29")
        private val UUID_MODEL = uuid16("2A24")
        private val UUID_FIRMWARE = uuid16("2A26")
        private val UUID_SOFTWARE = uuid16("2A28")

        private val UUID_BATTERY_SERVICE = uuid16("180F")
        private val UUID_BATTERY_LEVEL = uuid16("2A19")

        private val UUID_CCCD = uuid16("2902")

        private fun uuid16(short: String): UUID =
            UUID.fromString("0000${short.lowercase()}-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var logText: TextView
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var exportButton: Button
    private lateinit var analyzerText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val bluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private var connectedDevice: BluetoothDevice? = null

    private var manufacturer = "—"
    private var model = "—"
    private var firmware = "—"
    private var software = "—"
    private var battery = "—"
    private var fff0Found = false
    private var fff1Found = false
    private var fff2Found = false
    private var notifyCount = 0
    private var lastFff2Packet: ByteArray? = null
    private val packetLengthCounts = linkedMapOf<Int, Int>()
    private val changedByteHits = linkedMapOf<Int, Int>()
    private var currentMarker = "—"

    private data class LogEntry(
        val time: String,
        val source: String,
        val uuid: String,
        val hex: String,
        val ascii: String,
        val length: Int = 0,
        val changedIndexes: String = "",
        val marker: String = ""
    )

    private val entries = mutableListOf<LogEntry>()

    private sealed class GattOp {
        data class Read(val characteristic: BluetoothGattCharacteristic) : GattOp()
        data class EnableNotify(val characteristic: BluetoothGattCharacteristic) : GattOp()
    }

    private val gattQueue = ArrayDeque<GattOp>()
    private var gattBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateInfo()
        updateAnalyzer()
        logUi("SYSTEM", "", "v0.2 запущен. READ ONLY: FFF1 и OTA не используются. Анализируем только входящие FFF2.")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "G10 BLE Lab v0.2"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val safety = TextView(this).apply {
            text = "READ ONLY • пассивный анализ FFF2 • FFF1/OTA заблокированы"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(safety)

        statusText = TextView(this).apply {
            text = "Статус: готов"
            textSize = 17f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scanButton = Button(this).apply {
            text = "Сканировать G10"
            setOnClickListener { beginScanFlow() }
        }
        row.addView(scanButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        disconnectButton = Button(this).apply {
            text = "Отключить"
            isEnabled = false
            setOnClickListener { disconnectGatt() }
        }
        row.addView(disconnectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        exportButton = Button(this).apply {
            text = "Экспорт CSV"
            setOnClickListener { exportCsv() }
        }
        row2.addView(exportButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val clearButton = Button(this).apply {
            text = "Очистить журнал"
            setOnClickListener {
                entries.clear()
                logText.text = ""
                notifyCount = 0
                lastFff2Packet = null
                packetLengthCounts.clear()
                changedByteHits.clear()
                currentMarker = "—"
                updateInfo()
                updateAnalyzer()
            }
        }
        row2.addView(clearButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        val markerHeader = TextView(this).apply {
            text = "Метка теста (только помечает журнал, ничего не отправляет в самокат)"
            textSize = 14f
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(markerHeader)

        val markerRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("СТОИТ", "КОЛЕСО", "ТОРМОЗ").forEach { label ->
            val b = Button(this).apply {
                text = label
                setOnClickListener { setTestMarker(label) }
            }
            markerRow1.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(markerRow1)

        val markerRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ECO", "DRIVE", "SPORT").forEach { label ->
            val b = Button(this).apply {
                text = label
                setOnClickListener { setTestMarker(label) }
            }
            markerRow2.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(markerRow2)

        analyzerText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(analyzerText)

        infoText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(infoText)

        val logHeader = TextView(this).apply {
            text = "HEX-журнал FFF2 и диагностика"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(logHeader)

        logText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun beginScanFlow() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("Bluetooth не поддерживается")
            return
        }
        if (!adapter.isEnabled) {
            try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } catch (_: SecurityException) {
                toast("Разрешите Bluetooth в настройках телефона")
            }
            return
        }
        startScan()
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                REQ_PERMISSIONS
            )
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQ_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                beginScanFlow()
            } else {
                setStatus("Нет разрешения Nearby devices/Bluetooth")
                toast("Без разрешения Bluetooth приложение не сможет найти G10")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            setStatus("BLE-сканер недоступен")
            return
        }

        disconnectGatt()
        resetDeviceInfo()
        scanning = true
        scanButton.isEnabled = false
        scanButton.text = "Идёт поиск…"
        setStatus("Ищу BLE-устройство с именем G10…")
        logUi("SCAN", "", "Старт сканирования")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan()
                setStatus("G10 не найден за 15 секунд")
                logUi("SCAN", "", "Таймаут: G10 не найден")
            }
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning = false
        scanButton.isEnabled = true
        scanButton.text = "Сканировать G10"
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val deviceName = try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
            val name = advertisedName ?: deviceName

            if (name.equals("G10", ignoreCase = true)) {
                stopScan()
                connectedDevice = result.device
                val address = try { result.device.address } catch (_: Exception) { "?" }
                logUi("SCAN", "", "Найден G10 • $address • RSSI ${result.rssi} dBm")
                setStatus("G10 найден. Подключение…")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            setStatus("Ошибка BLE-сканирования: $errorCode")
            logUi("ERROR", "", "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            setStatus("Ошибка подключения: ${e.message}")
            logUi("ERROR", "", "connectGatt: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    setStatus("Подключено. Читаю GATT-сервисы…")
                    disconnectButton.isEnabled = true
                }
                logUi("GATT", "", "CONNECTED status=$status")
                handler.postDelayed({
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        logUi("ERROR", "", "discoverServices: ${e.message}")
                    }
                }, 250)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logUi("GATT", "", "DISCONNECTED status=$status")
                runOnUiThread {
                    setStatus("Отключено (status=$status)")
                    disconnectButton.isEnabled = false
                }
                synchronized(gattQueue) {
                    gattQueue.clear()
                    gattBusy = false
                }
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt === gatt) bluetoothGatt = null
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                logUi("ERROR", "", "Connection state=$newState status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Ошибка чтения GATT-сервисов: $status")
                logUi("ERROR", "", "onServicesDiscovered status=$status")
                return
            }

            val control = gatt.getService(UUID_GATT_FFF0)
            val fff1 = control?.getCharacteristic(UUID_GATT_FFF1)
            val fff2 = control?.getCharacteristic(UUID_GATT_FFF2)

            fff0Found = control != null
            fff1Found = fff1 != null
            fff2Found = fff2 != null

            logUi(
                "GATT",
                UUID_GATT_FFF0.toString(),
                "FFF0=${yesNo(fff0Found)} FFF1(write)=${yesNo(fff1Found)} FFF2(notify)=${yesNo(fff2Found)}"
            )
            if (fff1Found) {
                logUi("SAFETY", UUID_GATT_FFF1.toString(), "FFF1 найден, но запись программно НЕ используется")
            }

            val deviceInfo = gatt.getService(UUID_DEVICE_INFO)
            val batteryService = gatt.getService(UUID_BATTERY_SERVICE)

            synchronized(gattQueue) {
                gattQueue.clear()
                listOf(
                    deviceInfo?.getCharacteristic(UUID_MANUFACTURER),
                    deviceInfo?.getCharacteristic(UUID_MODEL),
                    deviceInfo?.getCharacteristic(UUID_FIRMWARE),
                    deviceInfo?.getCharacteristic(UUID_SOFTWARE),
                    batteryService?.getCharacteristic(UUID_BATTERY_LEVEL)
                ).filterNotNull().forEach { gattQueue.add(GattOp.Read(it)) }

                if (fff2 != null) {
                    gattQueue.add(GattOp.EnableNotify(fff2))
                }
                gattBusy = false
            }
            runOnUiThread {
                setStatus("Сервисы найдены. Читаю сведения…")
                updateInfo()
            }
            executeNextGattOp(gatt)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(gatt, characteristic, value, status)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotification(characteristic, characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handleNotification(characteristic, value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logUi(
                "GATT",
                descriptor.uuid.toString(),
                "CCCD write status=$status (это только подписка на уведомления)"
            )
            synchronized(gattQueue) { gattBusy = false }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { setStatus("FFF2 Notify включён. Жду пакеты…") }
            }
            executeNextGattOp(gatt)
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            when (characteristic.uuid) {
                UUID_MANUFACTURER -> manufacturer = value.toAscii()
                UUID_MODEL -> model = value.toAscii()
                UUID_FIRMWARE -> firmware = value.toAscii()
                UUID_SOFTWARE -> software = value.toAscii()
                UUID_BATTERY_LEVEL -> battery = if (value.isNotEmpty()) {
                    (value[0].toInt() and 0xFF).toString() + "%"
                } else "—"
            }
            logPacket("READ", characteristic.uuid, value)
            runOnUiThread { updateInfo() }
        } else {
            logUi("ERROR", characteristic.uuid.toString(), "Read status=$status")
        }
        synchronized(gattQueue) { gattBusy = false }
        executeNextGattOp(gatt)
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid == UUID_GATT_FFF2) {
            notifyCount++
            val changed = analyzeFff2(value)
            logPacket("FFF2_NOTIFY", characteristic.uuid, value, changed)
            runOnUiThread {
                updateInfo()
                updateAnalyzer()
            }
        } else {
            logPacket("NOTIFY", characteristic.uuid, value)
        }
    }

    private fun analyzeFff2(value: ByteArray): List<Int> {
        packetLengthCounts[value.size] = (packetLengthCounts[value.size] ?: 0) + 1
        val previous = lastFff2Packet
        val changed = mutableListOf<Int>()
        if (previous != null) {
            val max = maxOf(previous.size, value.size)
            for (i in 0 until max) {
                val a = previous.getOrNull(i)?.toInt()?.and(0xFF)
                val b = value.getOrNull(i)?.toInt()?.and(0xFF)
                if (a != b) {
                    changed.add(i)
                    changedByteHits[i] = (changedByteHits[i] ?: 0) + 1
                }
            }
        }
        lastFff2Packet = value.copyOf()
        return changed
    }

    private fun setTestMarker(label: String) {
        currentMarker = label
        logUi("MARKER", "", "Метка теста: $label")
        updateAnalyzer()
    }

    private fun updateAnalyzer() {
        if (!::analyzerText.isInitialized) return
        runOnUiThread {
            val lengths = if (packetLengthCounts.isEmpty()) "—" else
                packetLengthCounts.entries.joinToString(" • ") { "${it.key}B×${it.value}" }
            val hotBytes = changedByteHits.entries
                .sortedByDescending { it.value }
                .take(12)
                .joinToString(", ") { "#${it.key}(${it.value})" }
                .ifBlank { "—" }
            analyzerText.text = buildString {
                appendLine("Анализатор протокола v0.2")
                appendLine("Текущая метка: $currentMarker")
                appendLine("Длины FFF2: $lengths")
                append("Чаще меняются байты: $hotBytes")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeNextGattOp(gatt: BluetoothGatt) {
        val op: GattOp = synchronized(gattQueue) {
            if (gattBusy || gattQueue.isEmpty()) return
            gattBusy = true
            gattQueue.removeFirst()
        }

        val started = try {
            when (op) {
                is GattOp.Read -> {
                    logUi("GATT", op.characteristic.uuid.toString(), "Read request")
                    gatt.readCharacteristic(op.characteristic)
                }
                is GattOp.EnableNotify -> {
                    val characteristic = op.characteristic
                    val localOk = gatt.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(UUID_CCCD)
                    if (!localOk || cccd == null) {
                        logUi(
                            "ERROR",
                            characteristic.uuid.toString(),
                            "Не удалось подготовить Notify: local=$localOk cccd=${cccd != null}"
                        )
                        false
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                cccd,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            ) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(cccd)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logUi("ERROR", "", "GATT operation: ${e.message}")
            false
        }

        if (!started) {
            synchronized(gattQueue) { gattBusy = false }
            handler.postDelayed({ executeNextGattOp(gatt) }, 100)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        stopScan()
        synchronized(gattQueue) {
            gattQueue.clear()
            gattBusy = false
        }
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            try {
                gatt.disconnect()
                handler.postDelayed({
                    try { gatt.close() } catch (_: Exception) {}
                }, 500)
            } catch (_: Exception) {
                try { gatt.close() } catch (_: Exception) {}
            }
        }
        connectedDevice = null
        runOnUiThread { disconnectButton.isEnabled = false }
    }

    private fun resetDeviceInfo() {
        manufacturer = "—"
        model = "—"
        firmware = "—"
        software = "—"
        battery = "—"
        fff0Found = false
        fff1Found = false
        fff2Found = false
        notifyCount = 0
        lastFff2Packet = null
        packetLengthCounts.clear()
        changedByteHits.clear()
        currentMarker = "—"
        updateInfo()
        updateAnalyzer()
    }

    private fun updateInfo() {
        runOnUiThread {
            infoText.text = buildString {
                appendLine("Устройство: ${connectedDeviceName()}")
                appendLine("Manufacturer: $manufacturer")
                appendLine("Model: $model")
                appendLine("Firmware: $firmware")
                appendLine("Software: $software")
                appendLine("Battery Service: $battery")
                appendLine("FFF0: ${yesNo(fff0Found)} • FFF1: ${yesNo(fff1Found)} • FFF2: ${yesNo(fff2Found)}")
                appendLine("Пакетов FFF2: $notifyCount")
                append("Безопасность: FFF1 и OTA не используются; v0.2 только читает/помечает FFF2")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectedDeviceName(): String {
        val d = connectedDevice ?: return "—"
        return try {
            "${d.name ?: "G10"} (${d.address})"
        } catch (_: SecurityException) {
            "G10"
        }
    }

    private fun yesNo(value: Boolean) = if (value) "OK" else "нет"

    private fun setStatus(message: String) {
        runOnUiThread {
            statusText.text = "Статус: $message"
        }
    }

    private fun logPacket(
        source: String,
        uuid: UUID,
        bytes: ByteArray,
        changedIndexes: List<Int> = emptyList()
    ) {
        val hex = bytes.toHex()
        val ascii = bytes.toPrintableAscii()
        val time = timestampFormat.format(Date())
        val changedText = changedIndexes.joinToString(";")
        val marker = currentMarker.takeUnless { it == "—" } ?: ""
        synchronized(entries) {
            entries.add(
                LogEntry(
                    time = time,
                    source = source,
                    uuid = uuid.toString(),
                    hex = hex,
                    ascii = ascii,
                    length = bytes.size,
                    changedIndexes = changedText,
                    marker = marker
                )
            )
        }
        val changeLine = if (changedIndexes.isNotEmpty())
            "\nCHANGED: ${changedIndexes.joinToString(",")}" else ""
        val markerLine = if (marker.isNotBlank()) "\nMARKER: $marker" else ""
        val line = "$time  $source\n${uuid}\nLEN: ${bytes.size}\nHEX: $hex$changeLine$markerLine" +
            if (ascii.isNotBlank()) "\nASCII: $ascii\n\n" else "\n\n"
        runOnUiThread {
            logText.append(line)
        }
    }

    private fun logUi(source: String, uuid: String, message: String) {
        val time = timestampFormat.format(Date())
        synchronized(entries) {
            entries.add(LogEntry(time, source, uuid, "", message))
        }
        runOnUiThread {
            logText.append("$time  $source${if (uuid.isNotBlank()) "  $uuid" else ""}\n$message\n\n")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }

    private fun ByteArray.toAscii(): String =
        toString(Charsets.UTF_8).trimEnd('\u0000').trim()

    private fun ByteArray.toPrintableAscii(): String =
        map { b ->
            val v = b.toInt() and 0xFF
            if (v in 32..126) v.toChar() else '.'
        }.joinToString("").trim('.')

    private fun exportCsv() {
        if (entries.isEmpty()) {
            toast("Журнал пока пуст")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(
                Intent.EXTRA_TITLE,
                "g10_ble_${fileStampFormat.format(Date())}.csv"
            )
        }
        startActivityForResult(intent, REQ_EXPORT_CSV)
    }

    @Deprecated("Deprecated in Android API, kept for minSdk compatibility without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            beginScanFlow()
            return
        }
        if (requestCode == REQ_EXPORT_CSV && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            writeCsv(uri)
        }
    }

    private fun writeCsv(uri: Uri) {
        try {
            val snapshot = synchronized(entries) { entries.toList() }
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (writer == null) error("Не удалось открыть файл")
                writer.write("time,source,uuid,length,changed_indexes,marker,hex,ascii_or_note\n")
                snapshot.forEach { e ->
                    writer.write(
                        listOf(
                            e.time, e.source, e.uuid, e.length.toString(),
                            e.changedIndexes, e.marker, e.hex, e.ascii
                        )
                            .joinToString(",") { csvEscape(it) }
                    )
                    writer.write("\n")
                }
            }
            toast("CSV сохранён")
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun csvEscape(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    @Suppress("unused")
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        stopScan()
        disconnectGatt()
        super.onDestroy()
    }
}
