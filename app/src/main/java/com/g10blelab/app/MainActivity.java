package com.g10blelab.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_BLE_PERMISSIONS = 1001;
    private static final int REQ_CREATE_CSV = 1002;

    private static final UUID UUID_FFF0 =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF1 =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF2 =
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;
    private String currentMarker = "";

    private TextView statusText;
    private TextView speedText;
    private TextView batteryText;
    private TextView rawText;
    private TextView motionText;
    private TextView cruiseText;
    private TextView brakeText;
    private TextView modeText;
    private TextView packetText;
    private TextView hexText;
    private Button scanButton;

    private long packetCount = 0;
    private boolean hasTelemetry = false;
    private int currentSpeedKmh = -1;
    private boolean currentMoving = false;
    private byte[] previousPacket;

    private final List<String> csvRows = new ArrayList<>();
    private final ArrayDeque<String> visibleHexLines = new ArrayDeque<>();
    private static final int MAX_VISIBLE_HEX_LINES = 120;

    private final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager != null ? manager.getAdapter() : null;

        buildUi();
        resetCsv();

        if (bluetoothAdapter == null) {
            setStatus("Bluetooth недоступен на этом устройстве");
            scanButton.setEnabled(false);
            return;
        }

        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else {
                ensurePermissionsAndScan();
            }
        });
    }

    private void buildUi() {
        int pad = dp(12);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("G10 BLE Lab v0.3.3 — Cruise Probe");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText(
                "CRUISE PROBE: приложение читает FFF2 и может отправлять только команды режима " +
                "ECO = F0 4C 03 01, SPORT = F0 4C 03 02 и RACE = F0 4C 03 03. " +
                "Команды разрешены только при подтверждённой скорости 0 км/ч. " +
                "Управление Cruise, LOCK/UNLOCK, Zero Start и OTA не отправляется."
        );
        warning.setTextSize(12);
        warning.setPadding(0, dp(8), 0, dp(12));
        root.addView(warning);

        statusText = field(root, "Статус: готово", 15, false);

        scanButton = new Button(this);
        scanButton.setText("НАЙТИ И ПОДКЛЮЧИТЬ G10");
        root.addView(scanButton, fullWidth());

        TextView protocolTitle = new TextView(this);
        protocolTitle.setText("MODE TEST — только на стоящем самокате");
        protocolTitle.setTextSize(14);
        protocolTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        protocolTitle.setPadding(0, dp(8), 0, dp(3));
        root.addView(protocolTitle, fullWidth());

        LinearLayout modeTestRow = horizontalRow(root);

        Button ecoReturnButton = new Button(this);
        ecoReturnButton.setText("ECO");
        ecoReturnButton.setOnClickListener(v ->
                sendRideModeCommand(
                        new byte[]{(byte) 0xF0, (byte) 0x4C, (byte) 0x03, (byte) 0x01},
                        "SET_ECO"
                )
        );
        modeTestRow.addView(ecoReturnButton, weighted());

        Button sportTestButton = new Button(this);
        sportTestButton.setText("SPORT");
        sportTestButton.setOnClickListener(v ->
                sendRideModeCommand(
                        new byte[]{(byte) 0xF0, (byte) 0x4C, (byte) 0x03, (byte) 0x02},
                        "SET_SPORT"
                )
        );
        modeTestRow.addView(sportTestButton, weighted());

        Button raceTestButton = new Button(this);
        raceTestButton.setText("RACE");
        raceTestButton.setOnClickListener(v ->
                sendRideModeCommand(
                        new byte[]{(byte) 0xF0, (byte) 0x4C, (byte) 0x03, (byte) 0x03},
                        "SET_RACE"
                )
        );
        modeTestRow.addView(raceTestButton, weighted());

        TextView protocolHint = new TextView(this);
        protocolHint.setText(
                "ECO: F0 4C 03 01   •   SPORT: F0 4C 03 02   •   RACE: F0 4C 03 03\n" +
                "Отправка блокируется без телеметрии, при скорости выше 0 или при движении."
        );
        protocolHint.setTextSize(12);
        protocolHint.setPadding(0, 0, 0, dp(6));
        root.addView(protocolHint, fullWidth());

        speedText = field(root, "Скорость: — км/ч", 38, true);
        speedText.setGravity(Gravity.CENTER_HORIZONTAL);
        speedText.setPadding(0, dp(14), 0, dp(6));

        batteryText = field(root, "Батарея: — В", 24, true);
        batteryText.setGravity(Gravity.CENTER_HORIZONTAL);
        rawText = field(root, "RAW speed: —   byte[12]: —", 14, false);
        motionText = field(root, "Движение: —", 18, true);
        cruiseText = field(root, "Круиз активен: —", 18, true);
        brakeText = field(root, "Тормоз: —", 18, true);
        modeText = field(root, "Метка режима: —", 16, true);
        packetText = field(root, "Пакетов: 0", 14, false);

        TextView markerTitle = field(root, "Метки теста (только пометки CSV, самокатом не управляют):", 14, true);
        markerTitle.setPadding(0, dp(12), 0, dp(4));

        LinearLayout row1 = horizontalRow(root);
        addMarkerButton(row1, "СТОИТ");
        addMarkerButton(row1, "КОЛЕСО");
        addMarkerButton(row1, "ТОРМОЗ");

        LinearLayout row2 = horizontalRow(root);
        addMarkerButton(row2, "ECO");
        addMarkerButton(row2, "SPORT");
        addMarkerButton(row2, "RACE");

        LinearLayout cruiseProbeRow = horizontalRow(root);
        addMarkerButton(cruiseProbeRow, "КРУИЗ РАЗРЕШЁН");
        addMarkerButton(cruiseProbeRow, "КРУИЗ ЗАПРЕЩЁН");

        LinearLayout row3 = horizontalRow(root);

        Button clear = new Button(this);
        clear.setText("ОЧИСТИТЬ");
        clear.setOnClickListener(v -> clearLog());
        row3.addView(clear, weighted());

        Button export = new Button(this);
        export.setText("ЭКСПОРТ CSV");
        export.setOnClickListener(v -> exportCsv());
        row3.addView(export, weighted());

        TextView hexTitle = field(root, "Последние FFF2 пакеты:", 14, true);
        hexTitle.setPadding(0, dp(12), 0, dp(4));

        hexText = new TextView(this);
        hexText.setTextSize(11);
        hexText.setTypeface(Typeface.MONOSPACE);
        hexText.setText("—");
        hexText.setTextIsSelectable(true);
        root.addView(hexText, fullWidth());

        setContentView(scroll);
    }

    private TextView field(LinearLayout root, String text, float size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        tv.setPadding(0, dp(3), 0, dp(3));
        root.addView(tv, fullWidth());
        return tv;
    }

    private LinearLayout horizontalRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, fullWidth());
        return row;
    }

    private void addMarkerButton(LinearLayout row, String marker) {
        Button button = new Button(this);
        button.setText(marker);
        button.setOnClickListener(v -> setMarker(marker));
        row.addView(button, weighted());
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ensurePermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }

            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQ_BLE_PERMISSIONS);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_BLE_PERMISSIONS
                );
                return;
            }
        }

        startScan();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_BLE_PERMISSIONS) {
            return;
        }

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startScan();
        } else {
            setStatus("Нет разрешений Bluetooth");
            Toast.makeText(
                    this,
                    "Разреши Bluetooth/устройства поблизости в настройках приложения",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean bluetoothEnabled() {
        if (bluetoothAdapter == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return bluetoothAdapter.isEnabled();
    }

    private void startScan() {
        if (!bluetoothEnabled()) {
            setStatus("Включи Bluetooth");
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            setStatus("BLE Scanner недоступен");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            setStatus("Нет BLUETOOTH_SCAN");
            return;
        }

        closeGatt();
        scanning = true;
        scanButton.setText("ОСТАНОВИТЬ ПОИСК");
        setStatus("Ищу G10…");

        bleScanner.startScan(scanCallback);

        mainHandler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                setStatus("G10 не найден — попробуй ещё раз");
            }
        }, 15000);
    }

    private void stopScan() {
        if (!scanning) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            scanning = false;
            scanButton.setText("НАЙТИ И ПОДКЛЮЧИТЬ G10");
            return;
        }

        if (bleScanner != null) {
            bleScanner.stopScan(scanCallback);
        }
        scanning = false;
        scanButton.setText("НАЙТИ И ПОДКЛЮЧИТЬ G10");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = null;

            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }

            if (name == null &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                     checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                             == PackageManager.PERMISSION_GRANTED)) {
                try {
                    name = result.getDevice().getName();
                } catch (SecurityException ignored) {
                }
            }

            if ("G10".equalsIgnoreCase(name)) {
                stopScan();
                setStatus("G10 найден, подключаюсь…");
                connect(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                scanButton.setText("НАЙТИ И ПОДКЛЮЧИТЬ G10");
                setStatus("Ошибка BLE scan: " + errorCode);
            });
        }
    };

    private void connect(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            setStatus("Нет BLUETOOTH_CONNECT");
            return;
        }

        try {
            gatt = device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
            );
        } catch (SecurityException e) {
            setStatus("Ошибка разрешения CONNECT");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> setStatus("Подключено. Читаю сервисы…"));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                try {
                    bluetoothGatt.discoverServices();
                } catch (SecurityException ignored) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> setStatus("Отключено"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            BluetoothGattService service = bluetoothGatt.getService(UUID_FFF0);
            if (service == null) {
                runOnUiThread(() -> setStatus("FFF0 не найден"));
                return;
            }

            commandCharacteristic = service.getCharacteristic(UUID_FFF1);
            notifyCharacteristic = service.getCharacteristic(UUID_FFF2);

            if (notifyCharacteristic == null) {
                runOnUiThread(() -> setStatus("FFF2 не найден"));
                return;
            }

            final boolean fff1Present = commandCharacteristic != null;
            runOnUiThread(() ->
                    setStatus("FFF0 найден. FFF1: " +
                            (fff1Present ? "есть" : "нет") +
                            ". Подписываюсь на FFF2…")
            );

            enableNotifications(bluetoothGatt, notifyCharacteristic);
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt bluetoothGatt,
                BluetoothGattDescriptor descriptor,
                int status
        ) {
            if (UUID_CCCD.equals(descriptor.getUuid())) {
                runOnUiThread(() -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        setStatus("FFF2 Notify активно — данные идут");
                    } else {
                        setStatus("Ошибка CCCD: " + status);
                    }
                });
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic
        ) {
            if (UUID_FFF2.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value != null) {
                    handleFff2(value.clone());
                }
            }
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value
        ) {
            if (UUID_FFF2.equals(characteristic.getUuid()) && value != null) {
                handleFff2(value.clone());
            }
        }
    };

    private void enableNotifications(
            BluetoothGatt bluetoothGatt,
            BluetoothGattCharacteristic characteristic
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> setStatus("Нет BLUETOOTH_CONNECT"));
            return;
        }

        try {
            boolean ok = bluetoothGatt.setCharacteristicNotification(characteristic, true);
            if (!ok) {
                runOnUiThread(() -> setStatus("setCharacteristicNotification=false"));
                return;
            }

            BluetoothGattDescriptor cccd = characteristic.getDescriptor(UUID_CCCD);
            if (cccd == null) {
                runOnUiThread(() -> setStatus("CCCD у FFF2 не найден"));
                return;
            }

            byte[] enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = bluetoothGatt.writeDescriptor(cccd, enable);
                if (result != 0) {
                    runOnUiThread(() -> setStatus("writeDescriptor error: " + result));
                }
            } else {
                //noinspection deprecation
                cccd.setValue(enable);
                //noinspection deprecation
                boolean queued = bluetoothGatt.writeDescriptor(cccd);
                if (!queued) {
                    runOnUiThread(() -> setStatus("CCCD write не поставлен в очередь"));
                }
            }
        } catch (SecurityException e) {
            runOnUiThread(() -> setStatus("Ошибка Bluetooth permission"));
        }
    }

    private void sendRideModeCommand(byte[] command, String label) {
        if (!hasTelemetry) {
            Toast.makeText(
                    this,
                    "Сначала дождись телеметрии FFF2",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (currentSpeedKmh != 0 || currentMoving) {
            Toast.makeText(
                    this,
                    "Команда заблокирована: самокат должен стоять, скорость 0 км/ч",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (gatt == null || commandCharacteristic == null) {
            Toast.makeText(this, "FFF1 ещё не готов", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет BLUETOOTH_CONNECT", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int properties = commandCharacteristic.getProperties();

            int writeType =
                    (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

            boolean queued;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = gatt.writeCharacteristic(
                        commandCharacteristic,
                        command,
                        writeType
                );
                queued = result == 0;
            } else {
                //noinspection deprecation
                commandCharacteristic.setWriteType(writeType);
                //noinspection deprecation
                commandCharacteristic.setValue(command);
                //noinspection deprecation
                queued = gatt.writeCharacteristic(commandCharacteristic);
            }

            appendCsv(
                    "FFF1_TX",
                    UUID_FFF1.toString(),
                    command.length,
                    "",
                    label,
                    toHex(command),
                    queued ? "mode_command_sent" : "mode_command_failed"
            );

            Toast.makeText(
                    this,
                    queued
                            ? "Отправлено: " + toHex(command)
                            : "Команда не отправлена",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission error", Toast.LENGTH_LONG).show();
        }
    }

    private void handleFff2(byte[] packet) {
        if (packet.length < 20) {
            appendCsv("FFF2_NOTIFY", UUID_FFF2.toString(), packet.length,
                    "", currentMarker, toHex(packet), "short_packet");
            return;
        }

        packetCount++;

        int speedKmh = u8(packet[12]);
        int raw16 = u8(packet[10]) | (u8(packet[11]) << 8);

        int batteryRaw = u8(packet[4]) | (u8(packet[5]) << 8);
        double batteryVoltage = batteryRaw / 100.0;

        int flags = u8(packet[18]);
        boolean moving = (flags & 0x02) != 0;
        boolean cruise = (flags & 0x04) != 0;
        boolean brake = (flags & 0x08) != 0;

        hasTelemetry = true;
        currentSpeedKmh = speedKmh;
        currentMoving = moving;

        String changed = changedIndexes(previousPacket, packet);
        previousPacket = packet.clone();

        String hex = toHex(packet);

        appendCsv(
                "FFF2_NOTIFY",
                UUID_FFF2.toString(),
                packet.length,
                changed,
                currentMarker,
                hex,
                "speed=" + speedKmh +
                        ";raw16=" + raw16 +
                        ";batteryV=" + String.format(Locale.US, "%.2f", batteryVoltage) +
                        ";flags18=0x" + String.format(Locale.US, "%02X", flags)
        );

        final long count = packetCount;

        runOnUiThread(() -> {
            speedText.setText("Скорость: " + speedKmh + " км/ч");
            batteryText.setText(
                    "Батарея: " + String.format(Locale.US, "%.2f", batteryVoltage) + " В"
            );
            rawText.setText(
                    "RAW speed: " + raw16 +
                    "   byte[12]: " + speedKmh +
                    "   byte[18]: 0x" + String.format(Locale.US, "%02X", flags)
            );
            motionText.setText("Движение: " + (moving ? "ДА" : "НЕТ"));
            cruiseText.setText("Круиз активен: " + (cruise ? "ДА" : "НЕТ"));
            brakeText.setText("Тормоз: " + (brake ? "ВКЛ" : "ВЫКЛ"));
            packetText.setText("Пакетов: " + count);

            String line =
                    String.format(
                            Locale.US,
                            "%s  %2d km/h  f=%02X  %s",
                            shortTime(),
                            speedKmh,
                            flags,
                            hex
                    );

            visibleHexLines.addFirst(line);
            while (visibleHexLines.size() > MAX_VISIBLE_HEX_LINES) {
                visibleHexLines.removeLast();
            }

            StringBuilder sb = new StringBuilder();
            for (String s : visibleHexLines) {
                sb.append(s).append('\n');
            }
            hexText.setText(sb.toString());
        });
    }

    private int u8(byte value) {
        return value & 0xFF;
    }

    private String changedIndexes(byte[] oldPacket, byte[] newPacket) {
        if (oldPacket == null || oldPacket.length != newPacket.length) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newPacket.length; i++) {
            if (oldPacket[i] != newPacket[i]) {
                if (sb.length() > 0) sb.append(';');
                sb.append(i);
            }
        }
        return sb.toString();
    }

    private String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private void setMarker(String marker) {
        currentMarker = marker;
        modeText.setText("Метка режима: " + marker);

        appendCsv(
                "MARKER",
                "",
                0,
                "",
                marker,
                "",
                "Метка теста: " + marker
        );
    }

    private synchronized void resetCsv() {
        csvRows.clear();
        csvRows.add("time,source,uuid,length,changed_indexes,marker,hex,ascii_or_note");
    }

    private void clearLog() {
        packetCount = 0;
        previousPacket = null;
        currentMarker = "";
        hasTelemetry = false;
        currentSpeedKmh = -1;
        currentMoving = false;
        visibleHexLines.clear();
        resetCsv();

        speedText.setText("Скорость: — км/ч");
        batteryText.setText("Батарея: — В");
        rawText.setText("RAW speed: —   byte[12]: —");
        motionText.setText("Движение: —");
        cruiseText.setText("Круиз активен: —");
        brakeText.setText("Тормоз: —");
        modeText.setText("Метка режима: —");
        packetText.setText("Пакетов: 0");
        hexText.setText("—");

        Toast.makeText(this, "Журнал очищен", Toast.LENGTH_SHORT).show();
    }

    private synchronized void appendCsv(
            String source,
            String uuid,
            int length,
            String changed,
            String marker,
            String hex,
            String note
    ) {
        csvRows.add(
                csv(timestamp()) + "," +
                csv(source) + "," +
                csv(uuid) + "," +
                csv(String.valueOf(length)) + "," +
                csv(changed) + "," +
                csv(marker) + "," +
                csv(hex) + "," +
                csv(note)
        );
    }

    private String csv(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String timestamp() {
        synchronized (timestampFormat) {
            return timestampFormat.format(new Date());
        }
    }

    private String shortTime() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "g10_ble_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) +
                        ".csv"
        );
        startActivityForResult(intent, REQ_CREATE_CSV);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_CREATE_CSV ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        writeCsv(uri);
    }

    private void writeCsv(Uri uri) {
        final List<String> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(csvRows);
        }

        try {
            ContentResolver resolver = getContentResolver();
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("OutputStream=null");

                for (String row : snapshot) {
                    out.write(row.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
            }
            Toast.makeText(this, "CSV сохранён", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Ошибка CSV: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText("Статус: " + text));
    }

    private void closeGatt() {
        if (gatt == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            gatt = null;
            return;
        }

        try {
            gatt.close();
        } catch (SecurityException ignored) {
        }

        gatt = null;
        commandCharacteristic = null;
        notifyCharacteristic = null;
        hasTelemetry = false;
        currentSpeedKmh = -1;
        currentMoving = false;
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeGatt();
        super.onDestroy();
    }
}
