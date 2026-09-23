package com.g10blelab.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private SharedPreferences routePrefs;

    private final SimpleDateFormat fileStamp =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private FrameLayout content;
    private View dashboardTab;
    private View routeTab;
    private View tripTab;
    private View mapTab;
    private View batteryTab;
    private View labTab;
    private View moreTab;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean wasFresh;
    private String connectionStatus = "G10 не подключён";
    private G10BleManager.Telemetry liveTelemetry;
    private final List<Button> modeButtons = new ArrayList<>();
    private Button connectButton;
    private Button quickTripButton;
    private TextView rangeBoundsText;
    private TextView rangeBasisText;
    private TextView tripDistanceText;
    private TextView tripTimeText;
    private TextView tripMaxText;

    private final List<Button> navigationButtons = new ArrayList<>();

    private TextView connectionText;
    private TextView speedText;
    private TextView batteryText;
    private TextView modeText;
    private TextView cruiseText;
    private TextView brakeText;
    private TextView tripMiniText;
    private TextView dashboardSocText;
    private TextView dashboardRangeText;
    private TextView dashboardRouteText;

    private WebView routeWebView;
    private boolean routeMapReady = false;
    private double latestLatitude = Double.NaN;
    private double latestLongitude = Double.NaN;
    private double routeDestinationLatitude = Double.NaN;
    private double routeDestinationLongitude = Double.NaN;
    private String routeDestinationLabel = "Точка на карте";
    private String selectedRouteProfile = RouteEnergyEstimator.PROFILE_BALANCED;
    private EditText routeDistanceInput;
    private EditText routeLoadInput;
    private EditText routeClimbInput;
    private CheckBox routeRoundTripInput;
    private TextView routeDestinationText;
    private TextView routeResultText;
    private TextView routeDetailsText;
    private TextView routeAssumptionsText;
    private Button routeEcoButton;
    private Button routeBalancedButton;
    private Button routeFastButton;
    private RouteEnergyEstimator.Result lastRouteResult;

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
        routePrefs = getSharedPreferences("g10_route_planner", MODE_PRIVATE);
        selectedRouteProfile = RouteEnergyEstimator.normalizeProfile(
                routePrefs.getString("profile", RouteEnergyEstimator.PROFILE_BALANCED)
        );
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
        root.setBackgroundColor(Color.parseColor("#0B1118"));
        root.setPadding(dp(8), dp(8), dp(8), dp(6));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left + dp(8), bars.top + dp(8), bars.right + dp(8), bars.bottom + dp(6));
            } else {
                view.setPadding(dp(8), insets.getSystemWindowInsetTop() + dp(8), dp(8),
                        insets.getSystemWindowInsetBottom() + dp(6));
            }
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("G10 DRIVE");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView version = new TextView(this);
        version.setText("0.7 ALPHA");
        version.setTextSize(11);
        version.setTextColor(Color.parseColor("#8FA6B5"));
        header.addView(version);
        root.addView(header, fullWidth());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        dashboardTab = buildDashboard();
        routeTab = buildRouteTab();
        tripTab = buildTripTab();
        mapTab = buildMapTab();
        batteryTab = buildBatteryTab();
        labTab = buildLabTab();
        moreTab = buildMoreTab();
        for (View tab : new View[] {dashboardTab, routeTab, tripTab, mapTab, batteryTab, labTab, moreTab}) {
            content.addView(tab);
        }
        LinearLayout nav = new LinearLayout(this);
        String[] labels = {"ПРИБОРЫ", "МАРШРУТ", "ПОЕЗДКИ", "ЕЩЁ"};
        View[] tabs = {dashboardTab, routeTab, tripTab, moreTab};
        for (int i = 0; i < labels.length; i++) {
            Button button = tabButton(labels[i]);
            nav.addView(button, weighted());
            registerNavigation(button, tabs[i], i == 1);
        }
        root.addView(nav, fullWidth());
        setContentView(root);
        root.requestApplyInsets();
        showTab(dashboardTab);
    }

    private View buildDashboard() {
        LinearLayout box = verticalBox();
        LinearLayout connectCard = card(box);
        connectionText = field(connectCard, "○ G10 не подключён", 13, true);
        connectButton = new Button(this);
        connectButton.setText("ПОДКЛЮЧИТЬ G10");
        stylePrimaryButton(connectButton);
        connectButton.setOnClickListener(v -> ensureBleAndScan());
        connectCard.addView(connectButton, fullWidth());

        LinearLayout instruments = card(box);
        speedText = field(instruments, "—", 58, true);
        speedText.setGravity(Gravity.CENTER);
        TextView kmh = field(instruments, "км/ч", 13, false);
        kmh.setGravity(Gravity.CENTER);
        kmh.setTextColor(Color.parseColor("#8FA6B5"));
        TextView rangeLabel = field(instruments, "ОСТАЛОСЬ ПРИМЕРНО", 12, true);
        rangeLabel.setGravity(Gravity.CENTER);
        rangeLabel.setTextColor(Color.parseColor("#8FA6B5"));
        dashboardRangeText = field(instruments, "— км", 42, true);
        dashboardRangeText.setGravity(Gravity.CENTER);
        dashboardRangeText.setTextColor(Color.parseColor("#58D68D"));
        rangeBoundsText = field(instruments, "Подключите самокат для прогноза", 13, false);
        rangeBoundsText.setGravity(Gravity.CENTER);
        rangeBasisText = field(instruments, "", 12, false);
        rangeBasisText.setGravity(Gravity.CENTER);
        rangeBasisText.setTextColor(Color.parseColor("#8FA6B5"));
        dashboardSocText = field(instruments, "Заряд — • оценка по напряжению", 13, false);
        dashboardSocText.setGravity(Gravity.CENTER);

        LinearLayout stats1 = new LinearLayout(this);
        box.addView(stats1, fullWidth());
        tripDistanceText = metric(stats1, "ПОЕЗДКА", "0.00 км");
        tripTimeText = metric(stats1, "ВРЕМЯ", "00:00");
        LinearLayout stats2 = new LinearLayout(this);
        box.addView(stats2, fullWidth());
        tripMaxText = metric(stats2, "МАКС. СКОРОСТЬ", "— км/ч");
        batteryText = metric(stats2, "НАПРЯЖЕНИЕ", "— В");

        LinearLayout routeCard = card(box);
        field(routeCard, "ПЛАН МАРШРУТА", 12, true).setTextColor(Color.parseColor("#8FA6B5"));
        dashboardRouteText = field(routeCard, "Выберите, куда поедем", 17, true);
        LinearLayout actions = new LinearLayout(this);
        routeCard.addView(actions, fullWidth());
        Button route = new Button(this);
        route.setText("НА КАРТУ");
        styleChoiceButton(route, false);
        route.setOnClickListener(v -> { ensureLocationPermission(); showTab(routeTab); });
        actions.addView(route, weighted());
        quickTripButton = new Button(this);
        quickTripButton.setText("НАЧАТЬ");
        stylePrimaryButton(quickTripButton);
        quickTripButton.setOnClickListener(v -> {
            if (trips.isTripActive()) trips.stopTrip("manual");
            else { ensureLocationPermission(); trips.startTrip(true); }
            refreshTripUi();
        });
        actions.addView(quickTripButton, weighted());
        tripMiniText = field(routeCard, "Поездка не записывается", 12, false);

        LinearLayout modeCard = card(box);
        modeText = field(modeCard, "Режим: смотрите дисплей G10", 14, true);
        field(modeCard, "Переключение доступно при остановке и свежей связи", 12, false);
        LinearLayout modes = new LinearLayout(this);
        modeCard.addView(modes, fullWidth());
        modes.addView(modeButton("ECO", 1), weighted());
        modes.addView(modeButton("SPORT", 2), weighted());
        modes.addView(modeButton("RACE", 3), weighted());
        brakeText = field(modeCard, "Тормоз: —", 13, false);
        return wrap(box);
    }

    private TextView metric(LinearLayout row, String label, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(8), dp(8), dp(8));
        cell.setBackground(roundedBackground("#131E28", 14));
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(cell, params);
        field(cell, label, 10, true).setTextColor(Color.parseColor("#8FA6B5"));
        return field(cell, value, 22, true);
    }

    private View buildMoreTab() {
        LinearLayout box = verticalBox();
        field(box, "САМОКАТ И ДАННЫЕ", 22, true);
        addMenuButton(box, "БАТАРЕЯ И ОБУЧЕНИЕ", () -> showTab(batteryTab));
        addMenuButton(box, "ТРЕК GPS", () -> { ensureLocationPermission(); showTab(mapTab); });
        addMenuButton(box, "ЛАБОРАТОРИЯ BLE", () -> showTab(labTab));
        addMenuButton(box, "СПРАВОЧНИК КОМАНД", this::showCommandReference);
        addMenuButton(box, "ПОДКЛЮЧИТЬ ЗАНОВО", () -> ensureBleAndScan());
        field(box, "Профиль батареи, история и обучение хранятся на телефоне. " +
                "Карта и поиск адресов используют интернет.", 13, false);
        return wrap(box);
    }

    private void addMenuButton(LinearLayout box, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        styleChoiceButton(button, false);
        button.setOnClickListener(v -> action.run());
        box.addView(button, fullWidth());
    }

    private void showCommandReference() {
        WebView reference = new WebView(this);
        reference.setBackgroundColor(Color.parseColor("#131E28"));
        reference.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                }
                return true;
            }
        });
        reference.loadUrl("file:///android_asset/command_reference.html");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Команды G10")
                .setView(reference).setPositiveButton("ЗАКРЫТЬ", null).create();
        dialog.setOnDismissListener(d -> reference.destroy());
        dialog.show();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private View buildRouteTab() {
        LinearLayout box = verticalBox();

        field(box, "МАРШРУТ И ЗАПАС ХОДА", 23, true);
        TextView intro = field(
                box,
                "Выберите точку на карте или найдите адрес. Приложение построит варианты и покажет, сколько километров останется.",
                13,
                false
        );
        intro.setTextColor(Color.parseColor("#AFC0CC"));

        routeDestinationText = field(box, "Куда: точка не выбрана", 15, true);

        routeWebView = new WebView(this);
        routeWebView.setBackgroundColor(Color.parseColor("#101820"));
        routeWebView.getSettings().setJavaScriptEnabled(true);
        routeWebView.getSettings().setDomStorageEnabled(true);
        routeWebView.getSettings().setUserAgentString(
                "G10-Companion/0.7 Android personal route planner"
        );
        routeWebView.addJavascriptInterface(new RouteMapBridge(), "G10Route");
        routeWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                routeMapReady = true;
                pushLocationToRouteMap();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !("https".equalsIgnoreCase(uri.getScheme()) ||
                        "http".equalsIgnoreCase(uri.getScheme()))) return true;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        // HTTPS origin enables normal CORS for fetch; never enable universal file access.
        try (InputStream input = getAssets().open("route_map.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String origin = "https://appassets.androidplatform.net/";
            routeWebView.loadDataWithBaseURL(origin, bytes.toString("UTF-8"), "text/html", null, origin);
        } catch (Exception e) {
            routeDestinationText.setText("Карта недоступна; введите километраж вручную");
        }
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(380)
        );
        mapParams.setMargins(0, dp(6), 0, dp(8));
        box.addView(routeWebView, mapParams);

        LinearLayout settingsCard = card(box);
        field(settingsCard, "ПАРАМЕТРЫ РАСЧЁТА", 14, true)
                .setTextColor(Color.parseColor("#8FA6B5"));

        field(settingsCard, "Расстояние в одну сторону, км", 12, false)
                .setTextColor(Color.parseColor("#8FA6B5"));

        LinearLayout distanceRow = new LinearLayout(this);
        distanceRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsCard.addView(distanceRow, fullWidth());

        routeDistanceInput = decimalInput(
                "Расстояние в одну сторону, км",
                routePrefs.getFloat("distance_km", 0f),
                false
        );
        distanceRow.addView(routeDistanceInput, weighted());

        routeRoundTripInput = new CheckBox(this);
        routeRoundTripInput.setText("Туда и обратно");
        routeRoundTripInput.setTextColor(Color.parseColor("#F4F7FA"));
        routeRoundTripInput.setChecked(routePrefs.getBoolean("round_trip", false));
        routeRoundTripInput.setOnCheckedChangeListener((button, checked) ->
                refreshRouteEstimate(false));
        distanceRow.addView(routeRoundTripInput, weighted());

        field(settingsCard, "Стиль поездки", 13, true);
        LinearLayout profiles = new LinearLayout(this);
        profiles.setOrientation(LinearLayout.HORIZONTAL);
        settingsCard.addView(profiles, fullWidth());

        routeEcoButton = new Button(this);
        routeEcoButton.setText("ЭКО");
        routeBalancedButton = new Button(this);
        routeBalancedButton.setText("БАЛАНС");
        routeFastButton = new Button(this);
        routeFastButton.setText("БЫСТРО");
        profiles.addView(routeEcoButton, weighted());
        profiles.addView(routeBalancedButton, weighted());
        profiles.addView(routeFastButton, weighted());

        routeEcoButton.setOnClickListener(v -> selectRouteProfile(RouteEnergyEstimator.PROFILE_ECO));
        routeBalancedButton.setOnClickListener(v ->
                selectRouteProfile(RouteEnergyEstimator.PROFILE_BALANCED));
        routeFastButton.setOnClickListener(v ->
                selectRouteProfile(RouteEnergyEstimator.PROFILE_FAST));
        refreshRouteProfileButtons();

        field(settingsCard, "Водитель + груз, кг   /   подъём в одну сторону, м", 12, false)
                .setTextColor(Color.parseColor("#8FA6B5"));

        LinearLayout conditions = new LinearLayout(this);
        conditions.setOrientation(LinearLayout.HORIZONTAL);
        settingsCard.addView(conditions, fullWidth());

        routeLoadInput = decimalInput(
                "Водитель + груз, кг",
                routePrefs.getFloat("load_kg", 80f),
                false
        );
        routeClimbInput = decimalInput(
                "Подъём по пути, м",
                routePrefs.getFloat("climb_m", 0f),
                false
        );
        conditions.addView(routeLoadInput, weighted());
        conditions.addView(routeClimbInput, weighted());

        Button calculate = new Button(this);
        calculate.setText("РАССЧИТАТЬ ОСТАТОК");
        stylePrimaryButton(calculate);
        calculate.setOnClickListener(v -> refreshRouteEstimate(true));
        settingsCard.addView(calculate, fullWidth());

        LinearLayout resultCard = card(box);
        routeResultText = field(resultCard, "ВЫБЕРИТЕ МАРШРУТ", 23, true);
        routeResultText.setGravity(Gravity.CENTER_HORIZONTAL);
        routeDetailsText = field(
                resultCard,
                "После подключения G10 здесь появится прогноз остатка в километрах.",
                15,
                false
        );
        routeDetailsText.setGravity(Gravity.CENTER_HORIZONTAL);

        Button navigator = new Button(this);
        navigator.setText("ОТКРЫТЬ В НАВИГАТОРЕ");
        navigator.setOnClickListener(v -> openRouteInNavigator());
        resultCard.addView(navigator, fullWidth());

        routeAssumptionsText = field(
                box,
                "Пока ток BMS не расшифрован, результат показывается диапазоном. " +
                        "Он станет точнее после 3–5 обычных поездок.",
                12,
                false
        );
        routeAssumptionsText.setTextColor(Color.parseColor("#8FA6B5"));

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
                "Здесь остаётся технический GPS-трек поездки. Выбор точки назначения и варианты пути " +
                        "теперь находятся на вкладке МАРШРУТ.",
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
                "Для G10 установлен заводской профиль 48 В / 15,6 А·ч. " +
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
                        "отдельного ответа не найдено. Команда не считается подтверждённой и не отправляется.",
                12,
                true
        );
        lastTest.setPadding(0, 0, 0, dp(8));

        addMenuButton(box, "КОМАНДЫ И ИСТОЧНИКИ", this::showCommandReference);
        cruiseText = field(box, "Бит круиза: гипотеза, требуется проверка", 12, false);
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

    private final class RouteMapBridge {
        @JavascriptInterface
        public void onMapReady() {
            runOnUiThread(() -> {
                routeMapReady = true;
                pushLocationToRouteMap();
            });
        }

        @JavascriptInterface
        public void onRouteReady(
                double distanceKm,
                double destinationLat,
                double destinationLon,
                String label
        ) {
            runOnUiThread(() -> {
                if (!Double.isFinite(distanceKm) || distanceKm <= 0 || distanceKm > 500 ||
                        !Double.isFinite(destinationLat) || Math.abs(destinationLat) > 90 ||
                        !Double.isFinite(destinationLon) || Math.abs(destinationLon) > 180) return;
                routeDestinationLatitude = destinationLat;
                routeDestinationLongitude = destinationLon;
                routeDestinationLabel = label == null || label.trim().isEmpty()
                        ? "Точка на карте"
                        : label.trim();
                String shortLabel = routeDestinationLabel.length() > 90
                        ? routeDestinationLabel.substring(0, 87) + "…"
                        : routeDestinationLabel;
                routeDestinationText.setText("Куда: " + shortLabel);
                routeDistanceInput.setText(String.format(Locale.US, "%.2f", distanceKm));
                refreshRouteEstimate(false);
            });
        }

        @JavascriptInterface
        public void onRouteCleared(String label) {
            runOnUiThread(() -> {
                routeDestinationLatitude = Double.NaN;
                routeDestinationLongitude = Double.NaN;
                routeDistanceInput.setText("0");
                routeDestinationText.setText("Куда: " + (label == null ? "Точка на карте" : label));
                refreshRouteEstimate(false);
            });
        }

        @JavascriptInterface
        public void onRouteError(String message) {
            runOnUiThread(() -> {
                if (routeDestinationText != null && message != null) {
                    routeDestinationText.setText("Карта: " + message);
                }
            });
        }
    }

    private void pushLocationToRouteMap() {
        if (!routeMapReady || routeWebView == null ||
                !Double.isFinite(latestLatitude) || !Double.isFinite(latestLongitude)) {
            return;
        }
        routeWebView.evaluateJavascript(
                String.format(
                        Locale.US,
                        "window.g10SetStart && window.g10SetStart(%.7f, %.7f);",
                        latestLatitude,
                        latestLongitude
                ),
                null
        );
    }

    private void selectRouteProfile(String profile) {
        selectedRouteProfile = RouteEnergyEstimator.normalizeProfile(profile);
        refreshRouteProfileButtons();
        refreshRouteEstimate(false);
    }

    private void refreshRouteProfileButtons() {
        if (routeEcoButton == null) return;
        styleChoiceButton(
                routeEcoButton,
                RouteEnergyEstimator.PROFILE_ECO.equals(selectedRouteProfile)
        );
        styleChoiceButton(
                routeBalancedButton,
                RouteEnergyEstimator.PROFILE_BALANCED.equals(selectedRouteProfile)
        );
        styleChoiceButton(
                routeFastButton,
                RouteEnergyEstimator.PROFILE_FAST.equals(selectedRouteProfile)
        );
    }

    private RouteEnergyEstimator.Result estimateRange(double distance, boolean round, double load, double climb) {
        return RouteEnergyEstimator.estimate(new RouteEnergyEstimator.Input(distance, round, selectedRouteProfile,
                ble.hasFreshTelemetry() ? batteryCoach.getCurrentVoltage() : 0,
                batteryCoach.getFullVoltage(), batteryCoach.getReserveVoltage(), batteryCoach.getTemperatureC(),
                load, climb, batteryCoach.getKmPerVolt(), learnedRateForRouteProfile(selectedRouteProfile),
                batteryCoach.getLearningTripCount()));
    }

    private void refreshRouteEstimate(boolean showErrors) {
        if (routeDistanceInput == null) return;
        double distance = numberOr(routeDistanceInput, 0);
        double load = numberOr(routeLoadInput, 80);
        double climb = numberOr(routeClimbInput, 0);
        boolean round = routeRoundTripInput != null && routeRoundTripInput.isChecked();
        boolean valid = Double.isFinite(distance) && Double.isFinite(load) && Double.isFinite(climb) &&
                distance >= 0 && distance <= 500 && load >= 20 && load <= 200 && climb >= 0 && climb <= 10000;
        if (!valid) {
            routeResultText.setText("ПРОВЕРЬТЕ ПАРАМЕТРЫ");
            routeResultText.setTextColor(Color.parseColor("#FFB020"));
            routeDetailsText.setText("Нужны числовые значения расстояния, веса и подъёма.");
            dashboardRouteText.setText("Параметры маршрута требуют исправления");
            dashboardRangeText.setText("— км");
            rangeBoundsText.setText("Проверьте вес и параметры маршрута");
            rangeBasisText.setText("");
            if (aiRangeText != null) aiRangeText.setText("Прогноз: проверьте параметры маршрута");
            if (showErrors) Toast.makeText(this, "Проверьте расстояние, вес и подъём", Toast.LENGTH_LONG).show();
            return;
        }
        RouteEnergyEstimator.Result range = estimateRange(0, false, load, 0);
        // The dashboard and Battery screen share the same range; route relief is applied only to the route.
        dashboardRangeText.setText(range.hasBatteryData()
                ? String.format(Locale.US, "~%.0f км", range.expectedRangeKm) : "— км");
        dashboardRangeText.setTextColor(Color.parseColor(range.hasBatteryData() && range.expectedRangeKm <= 3
                ? "#FFB020" : "#58D68D"));
        rangeBoundsText.setText(range.hasBatteryData()
                ? String.format(Locale.US, "Ориентир %.0f–%.0f км · с запасом %.0f км",
                        range.safeRangeKm, range.optimisticRangeKm, range.safeRangeKm)
                : "Подключите G10 и дождитесь свежих данных");
        rangeBasisText.setText(range.hasBatteryData()
                ? routeProfileRussian(selectedRouteProfile) + " · " +
                        (range.personalized ? "по вашим поездкам" : "предварительная оценка") : "");
        dashboardSocText.setText(range.hasBatteryData()
                ? String.format(Locale.US, "Заряд ~%.0f%% · оценка по напряжению", range.currentSocPercent)
                : "Заряд — · нет свежих данных");
        if (aiRangeText != null) aiRangeText.setText(range.hasBatteryData()
                ? String.format(Locale.US, "Осталось ~%.0f км · диапазон %.0f–%.0f км\n%s",
                        range.expectedRangeKm, range.safeRangeKm, range.optimisticRangeKm,
                        routeProfileRussian(selectedRouteProfile)) : "Прогноз: нет свежей телеметрии");
        if (aiConfidenceText != null) aiConfidenceText.setText(range.personalized
                ? "Основа прогноза: ваши поездки; точность проверяется в пути"
                : "Основа прогноза: заводской ориентир; нужны минимум 3 качественные поездки");

        lastRouteResult = estimateRange(distance, round, load, climb);
        boolean changed = Math.abs(routePrefs.getFloat("distance_km", -1) - distance) > .001 ||
                Math.abs(routePrefs.getFloat("load_kg", -1) - load) > .001 ||
                Math.abs(routePrefs.getFloat("climb_m", -1) - climb) > .001 ||
                routePrefs.getBoolean("round_trip", !round) != round ||
                !selectedRouteProfile.equals(routePrefs.getString("profile", ""));
        if (changed) routePrefs.edit().putFloat("distance_km", (float) distance)
                .putFloat("load_kg", (float) load).putFloat("climb_m", (float) climb)
                .putBoolean("round_trip", round).putString("profile", selectedRouteProfile).apply();
        routeAssumptionsText.setText(String.format(Locale.US,
                "%s · %.0f°C · %.0f кг · подъём %.0f м%s. После маршрута указан запас для тех же условий.",
                routeProfileRussian(selectedRouteProfile), batteryCoach.getTemperatureC(), load, climb,
                round ? " на одну сторону; обратно принят такой же подъём" : ""));
        if (distance <= 0) {
            routeResultText.setText("ВЫБЕРИТЕ МАРШРУТ");
            routeResultText.setTextColor(Color.parseColor("#8FA6B5"));
            routeDetailsText.setText("Выберите точку на карте или введите километраж вручную.");
            dashboardRouteText.setText("Выберите, куда поедем");
            dashboardRouteText.setTextColor(Color.parseColor("#F4F7FA"));
            return;
        }
        routeResultText.setText(lastRouteResult.headlineRussian());
        routeResultText.setTextColor(routeStatusColor(lastRouteResult.status));
        routeDetailsText.setText(lastRouteResult.detailsRussian());
        String summary;
        if (!lastRouteResult.hasBatteryData()) summary = "Подключите G10 для расчёта";
        else if (lastRouteResult.shortfallKm() > 0) summary = String.format(Locale.US,
                "Не хватает примерно %.1f км", lastRouteResult.shortfallKm());
        else summary = String.format(Locale.US, "После маршрута ~%.1f км", lastRouteResult.arrivalRangeKm());
        dashboardRouteText.setText(String.format(Locale.US, "%s\nПуть %.1f км%s", summary,
                lastRouteResult.totalDistanceKm, round ? " · туда и обратно" : ""));
        dashboardRouteText.setTextColor(routeStatusColor(lastRouteResult.status));
    }

    private double learnedRateForRouteProfile(String profile) {
        if (RouteEnergyEstimator.PROFILE_ECO.equals(profile)) {
            return batteryCoach.getModeKmPerVolt("ECO");
        }
        if (RouteEnergyEstimator.PROFILE_FAST.equals(profile)) {
            double race = batteryCoach.getModeKmPerVolt("RACE");
            return race > 0 ? race : batteryCoach.getModeKmPerVolt("SPORT");
        }
        return 0;
    }

    private String routeProfileRussian(String profile) {
        if (RouteEnergyEstimator.PROFILE_ECO.equals(profile)) return "Экономичный режим";
        if (RouteEnergyEstimator.PROFILE_FAST.equals(profile)) return "Быстрый режим";
        return "Сбалансированный режим";
    }

    private int routeStatusColor(RouteEnergyEstimator.Status status) {
        if (status == RouteEnergyEstimator.Status.SAFE) {
            return Color.parseColor("#58D68D");
        }
        if (status == RouteEnergyEstimator.Status.TIGHT) {
            return Color.parseColor("#FFB020");
        }
        if (status == RouteEnergyEstimator.Status.INSUFFICIENT) {
            return Color.parseColor("#FF6B6B");
        }
        return Color.parseColor("#8FA6B5");
    }

    private double numberOr(EditText input, double fallback) {
        if (input == null) return fallback;
        try {
            String raw = input.getText().toString().trim().replace(',', '.');
            return raw.isEmpty() ? fallback : Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private void openRouteInNavigator() {
        if (!Double.isFinite(routeDestinationLatitude) ||
                !Double.isFinite(routeDestinationLongitude)) {
            Toast.makeText(this, "Сначала выберите точку назначения на карте", Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        String query = String.format(
                Locale.US,
                "%.7f,%.7f (%s)",
                routeDestinationLatitude,
                routeDestinationLongitude,
                routeDestinationLabel
        );
        Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(query))
        );
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "На телефоне не найдено приложение навигации", Toast.LENGTH_LONG)
                    .show();
        }
    }

    private Button tabButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setMinHeight(dp(52));
        b.setPadding(dp(2), dp(4), dp(2), dp(4));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextColor(Color.parseColor("#AFC0CC"));
        b.setBackground(roundedBackground("#16222D", 10));
        LinearLayout.LayoutParams p = weighted();
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(p);
        return b;
    }

    private Button modeButton(String text, int mode) {
        Button b = new Button(this);
        b.setText(text);
        styleChoiceButton(b, false);
        modeButtons.add(b);
        b.setOnClickListener(v -> {
            boolean ok = ble.sendMode(mode);
            if (!ok) {
                Toast.makeText(
                        this,
                        "Нужны свежие данные, остановка и пауза между командами",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                modeText.setText("Запрошен " + text + " · проверьте дисплей G10");
                Toast.makeText(this, "Команда отправлена. Проверьте режим на дисплее G10", Toast.LENGTH_SHORT).show();
            }
            refreshConnectionUi();
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

    private void registerNavigation(Button button, View tab, boolean needsLocation) {
        navigationButtons.add(button);
        button.setTag(tab);
        button.setOnClickListener(v -> {
            if (needsLocation) ensureLocationPermission();
            showTab(tab);
        });
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(roundedBackground("#131E28", 14));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(0, dp(5), 0, dp(5));
        root.addView(card, p);
        return card;
    }

    private GradientDrawable roundedBackground(String color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void stylePrimaryButton(Button button) {
        button.setMinHeight(dp(48));
        button.setTextColor(Color.parseColor("#07130D"));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(roundedBackground("#58D68D", 11));
    }

    private void styleChoiceButton(Button button, boolean selected) {
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(4), dp(4), dp(4), dp(4));
        button.setTextSize(13);
        button.setTextColor(Color.parseColor(selected ? "#07130D" : "#DCE7EF"));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(roundedBackground(selected ? "#58D68D" : "#263746", 9));
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
        t.setTextColor(Color.parseColor("#F4F7FA"));
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
        input.setTextColor(Color.parseColor("#F4F7FA"));
        input.setHintTextColor(Color.parseColor("#7F95A5"));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#58D68D")
        ));
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
            loadProfileInputs();
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

    private void loadRouteInputs() {
        if (routeDistanceInput == null) return;
        routeDistanceInput.setText(String.format(
                Locale.US,
                "%.2f",
                routePrefs.getFloat("distance_km", 0f)
        ));
        routeLoadInput.setText(String.format(
                Locale.US,
                "%.0f",
                routePrefs.getFloat("load_kg", 80f)
        ));
        routeClimbInput.setText(String.format(
                Locale.US,
                "%.0f",
                routePrefs.getFloat("climb_m", 0f)
        ));
        routeRoundTripInput.setChecked(routePrefs.getBoolean("round_trip", false));
        selectedRouteProfile = RouteEnergyEstimator.normalizeProfile(
                routePrefs.getString("profile", RouteEnergyEstimator.PROFILE_BALANCED)
        );
        refreshRouteProfileButtons();
        refreshRouteEstimate(false);
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
        routeTab.setVisibility(selected == routeTab ? View.VISIBLE : View.GONE);
        tripTab.setVisibility(selected == tripTab ? View.VISIBLE : View.GONE);
        mapTab.setVisibility(selected == mapTab ? View.VISIBLE : View.GONE);
        batteryTab.setVisibility(selected == batteryTab ? View.VISIBLE : View.GONE);
        labTab.setVisibility(selected == labTab ? View.VISIBLE : View.GONE);
        moreTab.setVisibility(selected == moreTab ? View.VISIBLE : View.GONE);

        for (Button button : navigationButtons) {
            boolean active = button.getTag() == selected || (button.getTag() == moreTab &&
                    (selected == batteryTab || selected == mapTab || selected == labTab));
            button.setTextColor(Color.parseColor(active ? "#07130D" : "#AFC0CC"));
            button.setBackground(roundedBackground(active ? "#58D68D" : "#16222D", 10));
        }

        if (selected == routeTab) {
            pushLocationToRouteMap();
            routeWebView.evaluateJavascript("window.g10MapResize && window.g10MapResize();", null);
        }
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
        runOnUiThread(() -> { connectionStatus = status; refreshConnectionUi(); });
    }

    private void refreshConnectionUi() {
        if (connectionText == null) return;
        boolean fresh = ble.hasFreshTelemetry();
        connectionText.setText(fresh ? "● G10 · телеметрия обновляется" :
                (liveTelemetry != null ? "○ Нет свежих данных · переподключитесь" : "○ " + connectionStatus));
        connectionText.setTextColor(Color.parseColor(fresh ? "#58D68D" : "#FFB020"));
        connectButton.setVisibility(fresh ? View.GONE : View.VISIBLE);
        for (Button button : modeButtons) {
            button.setEnabled(ble.canSendMode());
            button.setAlpha(button.isEnabled() ? 1f : .45f);
        }
        if (!fresh) {
            speedText.setText("—"); batteryText.setText("— В"); brakeText.setText("Тормоз: —");
            trips.invalidateTelemetry();
        } else if (liveTelemetry != null) {
            speedText.setText(String.valueOf(liveTelemetry.speedKmh));
            batteryText.setText(String.format(Locale.US, "%.1f В", liveTelemetry.batteryVoltage));
            brakeText.setText(liveTelemetry.brake ? "Тормоз нажат" : "Тормоз отпущен");
        }
        String requested = ble.getRequestedModeLabel();
        modeText.setText("—".equals(requested) ? "Режим: смотрите дисплей G10" :
                "Запрошен " + requested + " · проверьте дисплей G10");
        if (wasFresh != fresh) { wasFresh = fresh; refreshBatteryAi(); }
    }

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            refreshConnectionUi();
            if (trips.isTripActive()) refreshTripUi();
            uiHandler.postDelayed(this, 500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(uiTick);
        uiHandler.post(uiTick);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(uiTick);
        super.onPause();
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
        liveTelemetry = t;
        trips.onTelemetry(t);
        batteryCoach.updateTelemetry(t.speedKmh, t.batteryVoltage, t.modeLabel);
        refreshConnectionUi();
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
            latestLatitude = point.latitude;
            latestLongitude = point.longitude;
            locationText.setText(String.format(
                    Locale.US,
                    "GPS: %.6f, %.6f   ±%.0f м   alt %.1f м",
                    point.latitude, point.longitude, point.accuracy, point.altitude));
            trackView.setPoints(trips.getPointsSnapshot());
            pushLocationToRouteMap();
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

        tripDistanceText.setText(String.format(Locale.US, "%.2f км", km));
        tripTimeText.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
        tripMaxText.setText(String.format(Locale.US, "%d км/ч", trips.getMaxBleSpeed()));
        quickTripButton.setText(trips.isTripActive() ? "ЗАВЕРШИТЬ" : "НАЧАТЬ");
        tripMiniText.setText(trips.isTripActive() ? "Идёт запись · экран остаётся включённым" : "Поездка не записывается");
        if (trips.isTripActive()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        double v = ble.hasFreshTelemetry() ? batteryCoach.getCurrentVoltage() : 0;
        aiVoltageText.setText(v > 0
                ? String.format(Locale.US, "Напряжение: %.2f В", v)
                : "Напряжение: —");

        double soc = ble.hasFreshTelemetry() ? batteryCoach.getSocEstimatePercent() : -1;
        aiSocText.setText(soc >= 0
                ? String.format(Locale.US, "SOC: ~%.0f%% (оценка по напряжению)", soc)
                : "SOC: —");
        double sag = ble.hasFreshTelemetry() ? batteryCoach.getCurrentSag() : -1;
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

        refreshRouteEstimate(false);
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
                loadRouteInputs();
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
        uiHandler.removeCallbacks(uiTick);
        trips.stopMonitoring();
        ble.close();
        if (routeWebView != null) {
            routeWebView.removeJavascriptInterface("G10Route");
            routeWebView.destroy();
        }
        super.onDestroy();
    }
}
