package com.g10blelab.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity
        implements G10BleManager.Listener, TripTracker.Listener {

    private static final int REQ_BLE = 1001;
    private static final int REQ_LOCATION = 1002;
    private static final int REQ_EXPORT = 1003;
    private static final int REQ_IMPORT_BACKUP = 1004;

    private enum ExportKind {
        NONE, LAB, TRIP_CSV, TRIP_GPX, TRIP_KML, TRIP_REPORT, BACKUP
    }
    private ExportKind pendingExport = ExportKind.NONE;

    private G10BleManager ble;
    private TripTracker trips;
    private BatteryCoach batteryCoach;

    private final SimpleDateFormat fileStamp =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private FrameLayout content;
    private View dashboardTab;
    private View tripTab;
    private View mapTab;
    private View batteryTab;
    private View labTab;

    private TextView connectionText;
    private TextView speedText;
    private TextView batteryText;
    private TextView modeText;
    private TextView cruiseText;
    private TextView brakeText;
    private TextView tripMiniText;

    private TextView tripStateText;
    private TextView tripStatsText;
    private TextView historyText;

    private TextView locationText;
    private TrackView trackView;

    private TextView aiVoltageText;
    private TextView aiSagText;
    private TextView aiEfficiencyText;
    private TextView aiRangeText;
    private TextView aiLearningText;
    private TextView aiSocText;
    private TextView aiHealthText;
    private TextView aiConfidenceText;
    private TextView aiProfileText;
    private TextView aiVerdictText;
    private TextView aiTripReportText;
    private EditText aiFullVoltageInput;
    private EditText aiReserveVoltageInput;
    private EditText aiCapacityInput;
    private EditText aiTemperatureInput;
    private EditText aiCyclesInput;

    private TextView protocolText;
    private TextView gattText;
    private TextView hexText;
    private TextView labCountText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ble = new G10BleManager(this, this);
        trips = new TripTracker(this, this);
        batteryCoach = new BatteryCoach(this);
        trips.setReserveVoltage(batteryCoach.getReserveVoltage());

        buildUi();
        refreshTripUi();
        refreshHistory();
        refreshBatteryAi();

        if (hasLocationPermission()) {
            trips.startMonitoring();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("G10 Companion  v0.5.0 Alpha");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, fullWidth());

        TextView subtitle = new TextView(this);
        subtitle.setText("Поездки • локальная аналитика • Battery AI • LAB");
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(8));
        root.addView(subtitle, fullWidth());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabs, fullWidth());

        Button bDash = tabButton("ГЛАВНАЯ");
        Button bTrip = tabButton("ПОЕЗДКА");
        Button bMap = tabButton("КАРТА");
        Button bAi = tabButton("BAT AI");
        Button bLab = tabButton("LAB");

        tabs.addView(bDash, weighted());
        tabs.addView(bTrip, weighted());
        tabs.addView(bMap, weighted());
        tabs.addView(bAi, weighted());
        tabs.addView(bLab, weighted());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dashboardTab = buildDashboard();
        tripTab = buildTripTab();
        mapTab = buildMapTab();
        batteryTab = buildBatteryTab();
        labTab = buildLabTab();

        content.addView(dashboardTab);
        content.addView(tripTab);
        content.addView(mapTab);
        content.addView(batteryTab);
        content.addView(labTab);

        bDash.setOnClickListener(v -> showTab(dashboardTab));
        bTrip.setOnClickListener(v -> showTab(tripTab));
        bMap.setOnClickListener(v -> showTab(mapTab));
        bAi.setOnClickListener(v -> showTab(batteryTab));
        bLab.setOnClickListener(v -> showTab(labTab));

        setContentView(root);
        showTab(dashboardTab);
    }

    private View buildDashboard() {
        LinearLayout box = verticalBox();

        connectionText = field(box, "BLE: не подключено", 15, true);

        Button connect = new Button(this);
        connect.setText("НАЙТИ И ПОДКЛЮЧИТЬ G10");
        connect.setOnClickListener(v -> ensureBleAndScan());
        box.addView(connect, fullWidth());

        speedText = field(box, "0", 58, true);
        speedText.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView kmh = field(box, "км/ч", 14, false);
        kmh.setGravity(Gravity.CENTER_HORIZONTAL);

        batteryText = field(box, "Батарея: — В", 26, true);
        batteryText.setGravity(Gravity.CENTER_HORIZONTAL);

        modeText = field(box, "Режим: —", 20, true);
        cruiseText = field(box, "Круиз активен: НЕТ", 18, true);
        brakeText = field(box, "Тормоз: НЕТ", 18, true);
        tripMiniText = field(box, "Поездка: не активна", 16, false);

        TextView modeTitle = field(box, "Режим движения", 14, true);
        modeTitle.setPadding(0, dp(12), 0, dp(3));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(modes, fullWidth());

        Button eco = modeButton("ECO", 1);
        Button sport = modeButton("SPORT", 2);
        Button race = modeButton("RACE", 3);
        modes.addView(eco, weighted());
        modes.addView(sport, weighted());
        modes.addView(race, weighted());

        TextView safety = field(
                box,
                "Команды режима отправляются только при полученной телеметрии, скорости 0 и отсутствии движения.",
                12,
                false
        );
        safety.setPadding(0, dp(8), 0, dp(8));

        return wrap(box);
    }

    private View buildTripTab() {
        LinearLayout box = verticalBox();

        field(box, "ПОЕЗДКА", 22, true);
        tripStateText = field(box, "Состояние: ожидание", 17, true);
        tripStatsText = field(box, "Дистанция: 0.00 км", 17, false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row, fullWidth());

        Button start = new Button(this);
        start.setText("СТАРТ");
        start.setOnClickListener(v -> {
            ensureLocationPermission();
            trips.startTrip(true);
        });
        row.addView(start, weighted());

        Button stop = new Button(this);
        stop.setText("ФИНИШ");
        stop.setOnClickListener(v -> trips.stopTrip("manual"));
        row.addView(stop, weighted());

        LinearLayout exportRow1 = new LinearLayout(this);
        exportRow1.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(exportRow1, fullWidth());

        Button exportCsv = new Button(this);
        exportCsv.setText("CSV");
        exportCsv.setOnClickListener(v -> exportTripCsv());
        exportRow1.addView(exportCsv, weighted());

        Button exportGpx = new Button(this);
        exportGpx.setText("GPX");
        exportGpx.setOnClickListener(v -> exportTripGpx());
        exportRow1.addView(exportGpx, weighted());

        LinearLayout exportRow2 = new LinearLayout(this);
        exportRow2.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(exportRow2, fullWidth());

        Button exportKml = new Button(this);
        exportKml.setText("KML");
        exportKml.setOnClickListener(v -> exportTripKml());
        exportRow2.addView(exportKml, weighted());

        Button exportReport = new Button(this);
        exportReport.setText("ОТЧЁТ AI");
        exportReport.setOnClickListener(v -> exportTripReport());
        exportRow2.addView(exportReport, weighted());

        TextView auto = field(
                box,
                "AUTO: поездка стартует при движении G10 и завершается после 2 минут без движения. " +
                        "CSV хранит телеметрию, GPX подходит для трекеров, KML — для карт.",
                12,
                false
        );
        auto.setPadding(0, dp(8), 0, dp(10));

        field(box, "Последние поездки", 16, true);
        historyText = field(box, "—", 13, false);
        historyText.setTypeface(Typeface.MONOSPACE);

        return wrap(box);
    }

    private View buildMapTab() {
        LinearLayout box = verticalBox();

        field(box, "КАРТА ТРЕКА", 22, true);
        locationText = field(box, "GPS: нет данных", 14, false);

        trackView = new TrackView(this);
        box.addView(trackView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));

        TextView note = field(
                box,
                "Alpha: здесь рисуется реальная геометрия GPS-трека без интернет-карт. " +
                        "Подложку карты и маршрутизацию добавим следующим этапом.",
                12,
                false
        );
        note.setPadding(0, dp(8), 0, 0);

        return wrap(box);
    }

    private View buildBatteryTab() {
        LinearLayout box = verticalBox();

        field(box, "BATTERY AI — локальный анализ", 22, true);
        aiVoltageText = field(box, "Напряжение: —", 22, true);
        aiSocText = field(box, "SOC: —", 18, true);
        aiSagText = field(box, "Просадка: —", 18, false);
        aiEfficiencyText = field(box, "Обученная эффективность: данных мало", 18, false);
        aiRangeText = field(box, "Прогноз запаса: обучается", 18, true);
        aiHealthText = field(box, "Тренд SOH: обучается", 16, false);
        aiConfidenceText = field(box, "Уверенность: 0%", 15, false);
        aiLearningText = field(box, "Обучающих поездок: 0", 15, false);
        aiVerdictText = field(box, "Вердикт: идёт обучение", 15, true);
        aiVerdictText.setPadding(0, dp(8), 0, dp(10));

        TextView info = field(
                box,
                "SOC и тренд SOH — оценочные, пока реальный BMS-процент и ток не расшифрованы. " +
                        "Приложение не выдумывает Wh/км: оно учится по GPS, напряжению, режиму, " +
                        "рельефу, температуре, просадке и восстановлению.",
                13,
                false
        );
        info.setPadding(0, dp(12), 0, dp(8));

        field(box, "Последний подробный разбор", 16, true);
        aiTripReportText = field(box, batteryCoach.getLastTripReport(), 13, false);
        aiTripReportText.setTypeface(Typeface.MONOSPACE);
        aiTripReportText.setTextIsSelectable(true);

        TextView profileTitle = field(box, "Профиль батареи", 17, true);
        profileTitle.setPadding(0, dp(14), 0, dp(4));
        aiProfileText = field(box, "—", 13, false);

        LinearLayout voltageRow = new LinearLayout(this);
        voltageRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(voltageRow, fullWidth());
        aiFullVoltageInput = decimalInput(
                "Полный заряд, В",
                batteryCoach.getFullVoltage(),
                false
        );
        aiReserveVoltageInput = decimalInput(
                "Резерв, В",
                batteryCoach.getReserveVoltage(),
                false
        );
        voltageRow.addView(aiFullVoltageInput, weighted());
        voltageRow.addView(aiReserveVoltageInput, weighted());

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(profileRow, fullWidth());
        aiCapacityInput = decimalInput(
                "Ёмкость, А·ч",
                batteryCoach.getCapacityAh(),
                false
        );
        aiTemperatureInput = decimalInput(
                "Температура, °C",
                batteryCoach.getTemperatureC(),
                true
        );
        profileRow.addView(aiCapacityInput, weighted());
        profileRow.addView(aiTemperatureInput, weighted());

        aiCyclesInput = decimalInput(
                "Циклы зарядки (если известны)",
                batteryCoach.getCycleCount(),
                false
        );
        aiCyclesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(aiCyclesInput, fullWidth());

        Button saveProfile = new Button(this);
        saveProfile.setText("СОХРАНИТЬ ПРОФИЛЬ");
        saveProfile.setOnClickListener(v -> saveBatteryProfile());
        box.addView(saveProfile, fullWidth());

        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(backupRow, fullWidth());

        Button backup = new Button(this);
        backup.setText("КОПИЯ JSON");
        backup.setOnClickListener(v -> exportBackup());
        backupRow.addView(backup, weighted());

        Button restore = new Button(this);
        restore.setText("ВОССТАНОВИТЬ");
        restore.setOnClickListener(v -> importBackup());
        backupRow.addView(restore, weighted());

        TextView profileNote = field(
                box,
                "Если точная ёмкость с наклейки неизвестна — оставьте 0. " +
                        "Температуру меняйте перед поездкой. Копию JSON можно сохранить в Google Drive " +
                        "через системное окно файлов.",
                12,
                false
        );
        profileNote.setPadding(0, dp(4), 0, dp(8));

        return wrap(box);
    }

    private View buildLabTab() {
        LinearLayout box = verticalBox();

        field(box, "LAB — диагностика протокола", 22, true);

        TextView warn = field(
                box,
                "Неизвестные 55 AA / 5A A5 системы анализируются только пассивно. " +
                        "Свет, рекуперация, TCS, Zero Start, Cruise ON/OFF, LOCK/UNLOCK и OTA здесь не отправляются.",
                12,
                false
        );
        warn.setPadding(0, 0, 0, dp(8));

        TextView lastTest = field(
                box,
                "Последний тест 12.08.2026: после F0 4D 13 в 32 следующих кадрах FFF2 " +
                        "отдельного ответа не найдено. Команда не считается подтверждённой и в v0.5 не отправляется.",
                12,
                true
        );
        lastTest.setPadding(0, 0, 0, dp(8));

        protocolText = field(box, "Protocol Detector: —", 14, true);
        gattText = field(box, "GATT: —", 12, false);
        labCountText = field(box, "LAB rows: 0", 12, false);

        field(box, "Метки", 14, true);
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(r1, fullWidth());
        addMarker(r1, "СТОИТ");
        addMarker(r1, "КОЛЕСО");
        addMarker(r1, "ТОРМОЗ");

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(r2, fullWidth());
        addMarker(r2, "ECO");
        addMarker(r2, "SPORT");
        addMarker(r2, "RACE");

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(r3, fullWidth());
        addMarker(r3, "КРУИЗ ON");
        addMarker(r3, "КРУИЗ OFF");

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(actions, fullWidth());

        Button clear = new Button(this);
        clear.setText("ОЧИСТИТЬ LAB");
        clear.setOnClickListener(v -> {
            ble.clearLabLog();
            refreshLab();
        });
        actions.addView(clear, weighted());

        Button export = new Button(this);
        export.setText("ЭКСПОРТ LAB CSV");
        export.setOnClickListener(v -> exportLab());
        actions.addView(export, weighted());

        field(box, "Последние FFF2 / protocol packets • Δ = изменившиеся байты", 14, true);
        hexText = field(box, "—", 11, false);
        hexText.setTypeface(Typeface.MONOSPACE);
        hexText.setTextIsSelectable(true);

        return wrap(box);
    }

    private Button tabButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private Button modeButton(String text, int mode) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(v -> {
            boolean ok = ble.sendMode(mode);
            if (!ok) {
                Toast.makeText(
                        this,
                        "Команда заблокирована: G10 должен стоять, скорость 0 км/ч",
                        Toast.LENGTH_LONG
                ).show();
            }
        });
        return b;
    }

    private void addMarker(LinearLayout row, String marker) {
        Button b = new Button(this);
        b.setText(marker);
        b.setTextSize(10);
        b.setOnClickListener(v -> {
            ble.setMarker(marker);
            refreshLab();
        });
        row.addView(b, weighted());
    }

    private LinearLayout verticalBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(8), dp(6), dp(12));
        return box;
    }

    private ScrollView wrap(View child) {
        ScrollView s = new ScrollView(this);
        s.addView(child);
        return s;
    }

    private TextView field(LinearLayout root, String text, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        root.addView(t, fullWidth());
        return t;
    }

    private EditText decimalInput(String hint, double value, boolean signed) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(String.format(Locale.US, "%.1f", value));
        int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        input.setInputType(type);
        input.setPadding(dp(6), dp(2), dp(6), dp(2));
        return input;
    }

    private void saveBatteryProfile() {
        try {
            double full = number(aiFullVoltageInput);
            double reserve = number(aiReserveVoltageInput);
            double capacity = number(aiCapacityInput);
            double temperature = number(aiTemperatureInput);
            int cycles = (int) Math.round(number(aiCyclesInput));

            boolean saved = batteryCoach.updateProfile(
                    full,
                    reserve,
                    capacity,
                    temperature,
                    cycles
            );
            if (!saved) {
                Toast.makeText(
                        this,
                        "Проверьте профиль: полный заряд должен быть выше резерва минимум на 3 В",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            trips.setReserveVoltage(reserve);
            refreshBatteryAi();
            Toast.makeText(this, "Профиль батареи сохранён", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Введите числа во всех полях профиля", Toast.LENGTH_LONG).show();
        }
    }

    private double number(EditText input) throws NumberFormatException {
        if (input == null) throw new NumberFormatException("input is null");
        return Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
    }

    private void loadProfileInputs() {
        if (aiFullVoltageInput == null) return;
        aiFullVoltageInput.setText(String.format(Locale.US, "%.1f", batteryCoach.getFullVoltage()));
        aiReserveVoltageInput.setText(String.format(Locale.US, "%.1f", batteryCoach.getReserveVoltage()));
        aiCapacityInput.setText(String.format(Locale.US, "%.1f", batteryCoach.getCapacityAh()));
        aiTemperatureInput.setText(String.format(Locale.US, "%.1f", batteryCoach.getTemperatureC()));
        aiCyclesInput.setText(String.valueOf(batteryCoach.getCycleCount()));
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showTab(View selected) {
        dashboardTab.setVisibility(selected == dashboardTab ? View.VISIBLE : View.GONE);
        tripTab.setVisibility(selected == tripTab ? View.VISIBLE : View.GONE);
        mapTab.setVisibility(selected == mapTab ? View.VISIBLE : View.GONE);
        batteryTab.setVisibility(selected == batteryTab ? View.VISIBLE : View.GONE);
        labTab.setVisibility(selected == labTab ? View.VISIBLE : View.GONE);
    }

    private void ensureBleAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        },
                        REQ_BLE
                );
                return;
            }
        }
        ble.scanAndConnect();
    }

    private void ensureLocationPermission() {
        if (!hasLocationPermission()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQ_LOCATION
            );
        } else {
            trips.startMonitoring();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_BLE) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) ble.scanAndConnect();
        } else if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                trips.startMonitoring();
                Toast.makeText(this, "GPS включён для поездок", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onBleStatus(String status) {
        runOnUiThread(() -> connectionText.setText("BLE: " + status));
    }

    @Override
    public void onGattSummary(String summary) {
        runOnUiThread(() -> {
            gattText.setText(summary);
            refreshLab();
        });
    }

    @Override
    public void onTelemetry(G10BleManager.Telemetry t) {
        runOnUiThread(() -> {
            speedText.setText(String.valueOf(t.speedKmh));
            batteryText.setText(String.format(Locale.US, "Батарея: %.2f В", t.batteryVoltage));
            modeText.setText("Режим: " + t.modeLabel);
            cruiseText.setText("Круиз активен: " + (t.cruiseActive ? "ДА" : "НЕТ"));
            brakeText.setText("Тормоз: " + (t.brake ? "ДА" : "НЕТ"));
        });

        trips.onTelemetry(t);
        batteryCoach.updateTelemetry(t.speedKmh, t.batteryVoltage, t.modeLabel);
        refreshBatteryAi();
        refreshTripUi();
    }

    @Override
    public void onLabUpdated() {
        runOnUiThread(this::refreshLab);
    }

    private void refreshLab() {
        if (protocolText == null) return;
        protocolText.setText(ble.getProtocolSummary());
        gattText.setText(ble.getGattSummary());
        labCountText.setText("LAB rows: " + ble.getLabRowCount());
        hexText.setText(ble.getRecentHex());
    }

    @Override
    public void onTripStateChanged() {
        runOnUiThread(() -> {
            refreshTripUi();
            refreshHistory();
            refreshBatteryAi();
        });
    }

    @Override
    public void onLocationChanged(TripTracker.TripPoint point) {
        runOnUiThread(() -> {
            locationText.setText(String.format(
                    Locale.US,
                    "GPS: %.6f, %.6f   ±%.0f м   alt %.1f м",
                    point.latitude, point.longitude, point.accuracy, point.altitude));
            trackView.setPoints(trips.getPointsSnapshot());
            refreshTripUi();
        });
    }

    @Override
    public void onTripFinished(TripTracker.TripSummary summary) {
        batteryCoach.observeTrip(summary.analysis);
        runOnUiThread(() -> {
            Toast.makeText(
                    this,
                    String.format(Locale.US, "Поездка сохранена: %.2f км", summary.distanceKm),
                    Toast.LENGTH_LONG
            ).show();
            refreshHistory();
            refreshBatteryAi();
        });
    }

    private void refreshTripUi() {
        if (tripStateText == null) return;

        String state = trips.isTripActive()
                ? (trips.isManualTrip() ? "АКТИВНА (manual)" : "АКТИВНА (auto)")
                : "ожидание";

        tripStateText.setText("Состояние: " + state);

        long sec = trips.getDurationSeconds();
        double km = trips.getDistanceKm();
        double avg = sec > 0 ? km / (sec / 3600.0) : 0.0;
        TripAnalysisEngine.Result analysis = trips.getLastAnalysis();
        if (!trips.isTripActive() && analysis != null) {
            km = analysis.distanceKm;
            sec = analysis.movingSeconds;
            avg = analysis.averageMovingSpeedKmh;
        }

        String extra = analysis != null && !trips.isTripActive()
                ? String.format(
                        Locale.US,
                        "\nНабор высоты: %.0f м\nGPS: %.0f%% • нагрузка: %.0f/100",
                        analysis.elevationGainM,
                        analysis.gpsQualityPercent,
                        analysis.loadIndex
                )
                : "";

        tripStatsText.setText(String.format(
                        Locale.US,
                        "Дистанция: %.2f км\nВремя движения: %02d:%02d\nСредняя: %.1f км/ч\n" +
                                "Макс BLE: %d км/ч\nТочек GPS: %d",
                        km,
                        sec / 60,
                        sec % 60,
                        avg,
                        trips.getMaxBleSpeed(),
                        trips.getPointCount()
                ) + extra);

        tripMiniText.setText(
                trips.isTripActive()
                        ? String.format(Locale.US, "Поездка: %.2f км", km)
                        : "Поездка: не активна"
        );

        if (trackView != null) {
            trackView.setPoints(trips.getPointsSnapshot());
        }
    }

    private void refreshHistory() {
        if (historyText == null) return;
        List<String> h = trips.getHistory();
        if (h.isEmpty()) {
            historyText.setText("Пока нет завершённых поездок.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : h) {
            sb.append(s).append('\n');
        }
        historyText.setText(sb.toString().trim());
    }

    private void refreshBatteryAi() {
        if (aiVoltageText == null) return;

        double v = batteryCoach.getCurrentVoltage();
        aiVoltageText.setText(v > 0
                ? String.format(Locale.US, "Напряжение: %.2f В", v)
                : "Напряжение: —");

        double soc = batteryCoach.getSocEstimatePercent();
        aiSocText.setText(soc >= 0
                ? String.format(Locale.US, "SOC: ~%.0f%% (оценка по напряжению)", soc)
                : "SOC: —");

        double sag = batteryCoach.getCurrentSag();
        aiSagText.setText(sag >= 0
                ? String.format(
                        Locale.US,
                        "Текущая просадка: %.2f В • восстановление: %.2f В",
                        sag,
                        batteryCoach.getLatestRecoveryVoltage()
                )
                : "Текущая просадка: —");

        if (batteryCoach.hasLearnedEfficiency()) {
            double modeRate = batteryCoach.getModeKmPerVolt(batteryCoach.getCurrentMode());
            aiEfficiencyText.setText(String.format(
                    Locale.US,
                    "Эффективность: %.2f км/В%s",
                    batteryCoach.getKmPerVolt(),
                    modeRate > 0
                            ? String.format(
                                    Locale.US,
                                    " • %s %.2f км/В",
                                    batteryCoach.getCurrentMode(),
                                    modeRate
                            )
                            : ""
            ));
        } else {
            aiEfficiencyText.setText("Обученная эффективность: данных мало");
        }

        double range = batteryCoach.getForecastRangeKm();
        if (range >= 0) {
            aiRangeText.setText(String.format(
                    Locale.US,
                    "Прогноз до %.1f В: ~%.1f км • %s • %.0f°C",
                    batteryCoach.getReserveVoltage(),
                    range,
                    batteryCoach.getCurrentMode(),
                    batteryCoach.getTemperatureC()
            ));
        } else {
            aiRangeText.setText("Прогноз запаса: обучается");
        }

        double health = batteryCoach.getHealthTrendPercent();
        aiHealthText.setText(health >= 0
                ? String.format(
                        Locale.US,
                        "Тренд SOH: ~%.0f%% от лучших сопоставимых поездок",
                        health
                )
                : "Тренд SOH: нужны минимум 5 обучающих поездок");

        aiConfidenceText.setText(
                "Уверенность прогноза: " + batteryCoach.getConfidencePercent() + "%"
        );

        aiLearningText.setText(
                "Обучающих поездок: " + batteryCoach.getLearningTripCount()
        );

        aiVerdictText.setText("Вердикт: " + batteryCoach.getVerdict());
        aiTripReportText.setText(batteryCoach.getLastTripReport());

        double energyWh = batteryCoach.getNominalEnergyWh();
        aiProfileText.setText(String.format(
                Locale.US,
                "Полный %.1f В • резерв %.1f В • ёмкость %s • %.0f°C • циклы %d%s",
                batteryCoach.getFullVoltage(),
                batteryCoach.getReserveVoltage(),
                batteryCoach.getCapacityAh() > 0
                        ? String.format(Locale.US, "%.1f А·ч", batteryCoach.getCapacityAh())
                        : "не задана",
                batteryCoach.getTemperatureC(),
                batteryCoach.getCycleCount(),
                energyWh > 0
                        ? String.format(Locale.US, " • номинально ~%.0f Вт·ч", energyWh)
                        : ""
        ));
    }

    private void exportLab() {
        pendingExport = ExportKind.LAB;
        createDocument(
                "g10_lab_" + fileStamp.format(new Date()) + ".csv",
                "text/csv"
        );
    }

    private boolean ensureTripForExport() {
        if (trips.hasTripData() && trips.getPointCount() > 0) return true;
        Toast.makeText(this, "Нет данных поездки для экспорта", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void exportTripCsv() {
        if (!ensureTripForExport()) return;
        pendingExport = ExportKind.TRIP_CSV;
        createDocument(
                "g10_trip_" + fileStamp.format(new Date()) + ".csv",
                "text/csv"
        );
    }

    private void exportTripGpx() {
        if (!ensureTripForExport()) return;
        pendingExport = ExportKind.TRIP_GPX;
        createDocument(
                "g10_trip_" + fileStamp.format(new Date()) + ".gpx",
                "application/gpx+xml"
        );
    }

    private void exportTripKml() {
        if (!ensureTripForExport()) return;
        pendingExport = ExportKind.TRIP_KML;
        createDocument(
                "g10_trip_" + fileStamp.format(new Date()) + ".kml",
                "application/vnd.google-earth.kml+xml"
        );
    }

    private void exportTripReport() {
        if (trips.getLastAnalysis() == null) {
            Toast.makeText(this, "Нет данных поездки для экспорта", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExport = ExportKind.TRIP_REPORT;
        createDocument(
                "g10_trip_report_" + fileStamp.format(new Date()) + ".txt",
                "text/plain"
        );
    }

    private void exportBackup() {
        pendingExport = ExportKind.BACKUP;
        createDocument(
                "g10_backup_" + fileStamp.format(new Date()) + ".json",
                "application/json"
        );
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, REQ_IMPORT_BACKUP);
    }

    private void createDocument(String name, String mimeType) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeType);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_IMPORT_BACKUP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                AppDataBackup.importJson(this, readText(data.getData()));
                trips.setReserveVoltage(batteryCoach.getReserveVoltage());
                loadProfileInputs();
                refreshHistory();
                refreshBatteryAi();
                Toast.makeText(this, "Резервная копия восстановлена", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "Ошибка восстановления: " + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK ||
                data == null || data.getData() == null) {
            pendingExport = ExportKind.NONE;
            return;
        }

        Uri uri = data.getData();

        try {
            String text;
            switch (pendingExport) {
                case LAB:
                    text = ble.getLabCsv();
                    break;
                case TRIP_GPX:
                    text = trips.getTripGpx();
                    break;
                case TRIP_KML:
                    text = trips.getTripKml();
                    break;
                case TRIP_REPORT:
                    text = trips.getAnalysisReport();
                    break;
                case BACKUP:
                    text = AppDataBackup.exportJson(this);
                    break;
                case TRIP_CSV:
                default:
                    text = trips.getTripCsv();
                    break;
            }

            ContentResolver resolver = getContentResolver();
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("output stream is null");
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Файл сохранён", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        pendingExport = ExportKind.NONE;
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("input stream is null");
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 5_000_000) {
                    throw new IllegalStateException("резервная копия больше 5 МБ");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    protected void onDestroy() {
        trips.stopMonitoring();
        ble.close();
        super.onDestroy();
    }
}
