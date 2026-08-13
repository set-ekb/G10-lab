package com.g10blelab.app;

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
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class G10BleManager {

    public interface Listener {
        void onBleStatus(String status);
        void onGattSummary(String summary);
        void onTelemetry(Telemetry telemetry);
        void onLabUpdated();
    }

    public static class Telemetry {
        public final int speedKmh;
        public final int rawSpeed;
        public final double batteryVoltage;
        public final int flags18;
        public final boolean moving;
        public final boolean cruiseActive;
        public final boolean brake;
        public final String modeLabel;

        public Telemetry(
                int speedKmh,
                int rawSpeed,
                double batteryVoltage,
                int flags18,
                boolean moving,
                boolean cruiseActive,
                boolean brake,
                String modeLabel
        ) {
            this.speedKmh = speedKmh;
            this.rawSpeed = rawSpeed;
            this.batteryVoltage = batteryVoltage;
            this.flags18 = flags18;
            this.moving = moving;
            this.cruiseActive = cruiseActive;
            this.brake = brake;
            this.modeLabel = modeLabel;
        }
    }

    private static final UUID UUID_FFF0 =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF1 =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FFF2 =
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private boolean scanning = false;
    private Telemetry lastTelemetry;
    private String modeLabel = "—";
    private String marker = "";

    private long f0Frames = 0;
    private long aa55Frames = 0;
    private long a55aFrames = 0;
    private long otherFrames = 0;

    private String gattSummary = "GATT: —";

    private final List<String> labRows = new ArrayList<>();
    private final ArrayDeque<String> recentHex = new ArrayDeque<>();
    private static final int MAX_HEX = 90;
    private static final int MAX_LAB_ROWS = 5000;
    private long droppedLabRows = 0;

    private byte[] previousNotifyPacket;
    private long rateWindowStartMs = 0;
    private int rateWindowPackets = 0;
    private double notifyRateHz = 0;

    private final SimpleDateFormat ts =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public G10BleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;
        clearLabLog();
    }

    public void scanAndConnect() {
        if (adapter == null) {
            status("Bluetooth недоступен");
            return;
        }
        if (!adapter.isEnabled()) {
            status("Bluetooth выключен");
            return;
        }

        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                status("BLE Scanner недоступен");
                return;
            }

            closeGattOnly();
            scanning = true;
            status("поиск G10…");
            scanner.startScan(scanCallback);

            main.postDelayed(() -> {
                if (scanning) {
                    stopScan();
                    status("G10 не найден");
                }
            }, 12000);
        } catch (SecurityException e) {
            status("нет Bluetooth permission");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || result == null) return;

            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }

            if (name == null) {
                try {
                    name = result.getDevice().getName();
                } catch (SecurityException ignored) {
                }
            }

            if (!"G10".equalsIgnoreCase(name)) return;

            stopScan();
            status("G10 найден, подключение…");

            try {
                BluetoothDevice device = result.getDevice();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(
                            context,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE
                    );
                } else {
                    gatt = device.connectGatt(context, false, gattCallback);
                }
            } catch (SecurityException e) {
                status("ошибка permission при подключении");
            }
        }
    };

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(
                BluetoothGatt bluetoothGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                G10BleManager.this.gatt = bluetoothGatt;
                status("подключено, discovery…");
                try {
                    bluetoothGatt.discoverServices();
                } catch (SecurityException e) {
                    status("нет permission для discovery");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("отключено");
                commandCharacteristic = null;
                notifyCharacteristic = null;
                lastTelemetry = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            BluetoothGattService fff0 = bluetoothGatt.getService(UUID_FFF0);
            if (fff0 == null) {
                status("FFF0 не найден");
                logGattProfile(bluetoothGatt);
                return;
            }

            commandCharacteristic = fff0.getCharacteristic(UUID_FFF1);
            notifyCharacteristic = fff0.getCharacteristic(UUID_FFF2);

            logGattProfile(bluetoothGatt);
            gattSummary = buildGattSummary(bluetoothGatt);
            postGattSummary();

            if (notifyCharacteristic == null) {
                status("FFF2 не найден");
                return;
            }

            status("FFF0/FFF1/FFF2 найдены, включаю Notify…");
            enableNotifications(bluetoothGatt, notifyCharacteristic);
        }

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value
        ) {
            if (UUID_FFF2.equals(characteristic.getUuid()) && value != null) {
                handlePacket(value.clone());
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic
        ) {
            if (UUID_FFF2.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value != null) handlePacket(value.clone());
            }
        }
    };

    private void enableNotifications(
            BluetoothGatt bluetoothGatt,
            BluetoothGattCharacteristic characteristic
    ) {
        try {
            bluetoothGatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor cccd = characteristic.getDescriptor(UUID_CCCD);

            if (cccd == null) {
                status("CCCD у FFF2 не найден");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeDescriptor(
                        cccd,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                );
            } else {
                //noinspection deprecation
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                //noinspection deprecation
                bluetoothGatt.writeDescriptor(cccd);
            }

            status("FFF2 Notify активно");
        } catch (SecurityException e) {
            status("ошибка permission при Notify");
        }
    }

    private void handlePacket(byte[] packet) {
        String changedIndexes = changedIndexes(previousNotifyPacket, packet);
        previousNotifyPacket = packet.clone();
        updateNotifyRate();
        classify(packet);
        appendRecentHex(packet, changedIndexes);

        if (packet.length < 20) {
            addLabRowDetailed(
                    "FFF2_NOTIFY",
                    UUID_FFF2.toString(),
                    packet.length,
                    changedIndexes,
                    marker,
                    toHex(packet),
                    "short_packet"
            );
            labUpdated();
            return;
        }

        if (allFF(packet)) {
            addLabRowDetailed(
                    "FFF2_NOTIFY",
                    UUID_FFF2.toString(),
                    packet.length,
                    changedIndexes,
                    marker,
                    toHex(packet),
                    "all_ff_ignored"
            );
            labUpdated();
            return;
        }

        int speed = u8(packet[12]);
        int raw = u8(packet[10]) | (u8(packet[11]) << 8);
        int batteryRaw = u8(packet[4]) | (u8(packet[5]) << 8);
        double voltage = batteryRaw / 100.0;

        int flags = u8(packet[18]);
        boolean moving = (flags & 0x02) != 0;
        boolean cruise = (flags & 0x04) != 0;
        boolean brake = (flags & 0x08) != 0;

        Telemetry t = new Telemetry(
                speed, raw, voltage, flags, moving, cruise, brake, modeLabel
        );
        lastTelemetry = t;

        addLabRowDetailed(
                "FFF2_NOTIFY",
                UUID_FFF2.toString(),
                packet.length,
                changedIndexes,
                marker,
                toHex(packet),
                "speed=" + speed +
                        ";raw16=" + raw +
                        ";batteryV=" + String.format(Locale.US, "%.2f", voltage) +
                        ";flags18=0x" + String.format(Locale.US, "%02X", flags)
        );

        main.post(() -> listener.onTelemetry(t));
        labUpdated();
    }

    public boolean sendMode(int mode) {
        if (mode < 1 || mode > 3) return false;
        if (lastTelemetry == null ||
                lastTelemetry.speedKmh != 0 ||
                lastTelemetry.moving ||
                gatt == null ||
                commandCharacteristic == null) {
            return false;
        }

        byte[] command = new byte[]{
                (byte) 0xF0,
                (byte) 0x4C,
                (byte) 0x03,
                (byte) mode
        };

        boolean queued = writeFff1(command);

        String label = mode == 1 ? "ECO" : mode == 2 ? "SPORT" : "RACE";

        addLabRow(
                "FFF1_TX",
                UUID_FFF1.toString(),
                command.length,
                marker,
                toHex(command),
                "SET_" + label + ";queued=" + queued
        );

        if (queued) {
            modeLabel = label;
            Telemetry old = lastTelemetry;
            if (old != null) {
                lastTelemetry = new Telemetry(
                        old.speedKmh,
                        old.rawSpeed,
                        old.batteryVoltage,
                        old.flags18,
                        old.moving,
                        old.cruiseActive,
                        old.brake,
                        modeLabel
                );
                Telemetry updated = lastTelemetry;
                main.post(() -> listener.onTelemetry(updated));
            }
        }

        labUpdated();
        return queued;
    }

    private boolean writeFff1(byte[] data) {
        try {
            int props = commandCharacteristic.getProperties();
            int writeType =
                    (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = gatt.writeCharacteristic(
                        commandCharacteristic,
                        data,
                        writeType
                );
                return result == 0;
            } else {
                //noinspection deprecation
                commandCharacteristic.setWriteType(writeType);
                //noinspection deprecation
                commandCharacteristic.setValue(data);
                //noinspection deprecation
                return gatt.writeCharacteristic(commandCharacteristic);
            }
        } catch (SecurityException e) {
            status("ошибка permission при FFF1 write");
            return false;
        }
    }

    private void classify(byte[] p) {
        String family;
        if (p.length >= 2 &&
                ((u8(p[0]) == 0x24 && u8(p[1]) == 0x22) || u8(p[0]) == 0xF1)) {
            f0Frames++;
            family = "G10_F0";
        } else if (p.length >= 2 && u8(p[0]) == 0x55 && u8(p[1]) == 0xAA) {
            aa55Frames++;
            family = "55_AA";
        } else if (p.length >= 2 && u8(p[0]) == 0x5A && u8(p[1]) == 0xA5) {
            a55aFrames++;
            family = "5A_A5";
        } else {
            otherFrames++;
            family = "OTHER";
        }

        addLabRow(
                "PROTOCOL_DETECT",
                UUID_FFF2.toString(),
                p.length,
                marker,
                toHex(p),
                "family=" + family
        );
    }

    public String getProtocolSummary() {
        return "Protocol Detector: G10/F0=" + f0Frames +
                " | 55 AA=" + aa55Frames +
                " | 5A A5=" + a55aFrames +
                " | OTHER=" + otherFrames +
                String.format(Locale.US, " | %.1f pkt/s", notifyRateHz) +
                (droppedLabRows > 0 ? " | limit −" + droppedLabRows : "");
    }

    private String buildGattSummary(BluetoothGatt bluetoothGatt) {
        int services = 0;
        int chars = 0;
        boolean fff0 = false;
        boolean fff1 = false;
        boolean fff2 = false;

        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            services++;
            if (UUID_FFF0.equals(service.getUuid())) fff0 = true;

            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                chars++;
                if (UUID_FFF1.equals(c.getUuid())) fff1 = true;
                if (UUID_FFF2.equals(c.getUuid())) fff2 = true;
            }
        }

        return "GATT: services=" + services +
                ", chars=" + chars +
                ", FFF0=" + yesNo(fff0) +
                ", FFF1=" + yesNo(fff1) +
                ", FFF2=" + yesNo(fff2);
    }

    private void logGattProfile(BluetoothGatt bluetoothGatt) {
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            addLabRow(
                    "GATT_SERVICE",
                    service.getUuid().toString(),
                    0,
                    "",
                    "",
                    "service"
            );

            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                addLabRow(
                        "GATT_CHAR",
                        c.getUuid().toString(),
                        0,
                        "",
                        "",
                        "service=" + service.getUuid() +
                                ";properties=0x" +
                                String.format(Locale.US, "%02X", c.getProperties()) +
                                ";props=" + properties(c.getProperties())
                );
            }
        }
        labUpdated();
    }

    private String properties(int p) {
        List<String> list = new ArrayList<>();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) list.add("READ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list.add("WRITE");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list.add("WRITE_NR");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list.add("NOTIFY");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list.add("INDICATE");
        return list.isEmpty() ? "NONE" : String.join("|", list);
    }

    public void setMarker(String marker) {
        this.marker = marker == null ? "" : marker;
        addLabRow("MARKER", "", 0, this.marker, "", "marker");
        labUpdated();
    }

    public void clearLabLog() {
        labRows.clear();
        recentHex.clear();
        f0Frames = 0;
        aa55Frames = 0;
        a55aFrames = 0;
        otherFrames = 0;
        droppedLabRows = 0;
        previousNotifyPacket = null;
        rateWindowStartMs = 0;
        rateWindowPackets = 0;
        notifyRateHz = 0;
        labRows.add(
                "time,source,uuid,length,changed_indexes,marker,hex,note"
        );
        labUpdated();
    }

    public int getLabRowCount() {
        return Math.max(0, labRows.size() - 1);
    }

    public String getLabCsv() {
        StringBuilder sb = new StringBuilder();
        for (String row : labRows) sb.append(row).append('\n');
        return sb.toString();
    }

    public String getRecentHex() {
        if (recentHex.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (String s : recentHex) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    public String getGattSummary() {
        return gattSummary;
    }

    private void appendRecentHex(byte[] p, String changedIndexes) {
        String delta = changedIndexes == null || changedIndexes.isEmpty()
                ? ""
                : "  Δ[" + changedIndexes + "]";
        recentHex.addLast(ts.format(new Date()) + delta + "  " + toHex(p));
        while (recentHex.size() > MAX_HEX) recentHex.removeFirst();
    }

    private void addLabRow(
            String source,
            String uuid,
            int length,
            String marker,
            String hex,
            String note
    ) {
        addLabRowDetailed(source, uuid, length, "", marker, hex, note);
    }

    private void addLabRowDetailed(
            String source,
            String uuid,
            int length,
            String changedIndexes,
            String marker,
            String hex,
            String note
    ) {
        while (labRows.size() >= MAX_LAB_ROWS + 1) {
            labRows.remove(1);
            droppedLabRows++;
        }
        labRows.add(
                csv(ts.format(new Date())) + "," +
                        csv(source) + "," +
                        csv(uuid) + "," +
                        csv(String.valueOf(length)) + "," +
                        csv(changedIndexes) + "," +
                        csv(marker) + "," +
                        csv(hex) + "," +
                        csv(note)
        );
    }

    private String changedIndexes(byte[] previous, byte[] current) {
        if (previous == null || current == null) return "";
        StringBuilder sb = new StringBuilder();
        int max = Math.max(previous.length, current.length);
        for (int i = 0; i < max; i++) {
            boolean changed = i >= previous.length || i >= current.length ||
                    previous[i] != current[i];
            if (changed) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(i);
            }
        }
        return sb.toString();
    }

    private void updateNotifyRate() {
        long now = System.currentTimeMillis();
        if (rateWindowStartMs == 0) rateWindowStartMs = now;
        rateWindowPackets++;
        long elapsed = now - rateWindowStartMs;
        if (elapsed >= 2000) {
            notifyRateHz = rateWindowPackets * 1000.0 / elapsed;
            rateWindowStartMs = now;
            rateWindowPackets = 0;
        }
    }

    private String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String yesNo(boolean v) {
        return v ? "YES" : "NO";
    }

    private int u8(byte b) {
        return b & 0xFF;
    }

    private boolean allFF(byte[] p) {
        if (p.length == 0) return false;
        for (byte b : p) {
            if ((b & 0xFF) != 0xFF) return false;
        }
        return true;
    }

    public static String toHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private void status(String text) {
        main.post(() -> listener.onBleStatus(text));
    }

    private void postGattSummary() {
        main.post(() -> listener.onGattSummary(gattSummary));
    }

    private void labUpdated() {
        main.post(listener::onLabUpdated);
    }

    private void closeGattOnly() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
        }
        gatt = null;
        commandCharacteristic = null;
        notifyCharacteristic = null;
        lastTelemetry = null;
    }

    public void close() {
        stopScan();
        closeGattOnly();
    }
}
