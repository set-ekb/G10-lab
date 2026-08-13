package com.g10blelab.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TripTracker {

    public interface Listener {
        void onTripStateChanged();
        void onLocationChanged(TripPoint point);
        void onTripFinished(TripSummary summary);
    }

    public static class TripPoint {
        public final long timestamp;
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final float accuracy;
        public final float gpsSpeedKmh;
        public final int bleSpeedKmh;
        public final double batteryVoltage;
        public final String mode;
        public final boolean cruise;
        public final boolean brake;

        public TripPoint(
                long timestamp,
                double latitude,
                double longitude,
                double altitude,
                float accuracy,
                float gpsSpeedKmh,
                int bleSpeedKmh,
                double batteryVoltage,
                String mode,
                boolean cruise,
                boolean brake
        ) {
            this.timestamp = timestamp;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracy = accuracy;
            this.gpsSpeedKmh = gpsSpeedKmh;
            this.bleSpeedKmh = bleSpeedKmh;
            this.batteryVoltage = batteryVoltage;
            this.mode = mode;
            this.cruise = cruise;
            this.brake = brake;
        }
    }

    public static class TripSummary {
        public final long startTime;
        public final long endTime;
        public final double distanceKm;
        public final int maxBleSpeed;
        public final double startBatteryVoltage;
        public final double endBatteryVoltage;
        public final int pointCount;
        public final TripAnalysisEngine.Result analysis;

        public TripSummary(
                long startTime,
                long endTime,
                double distanceKm,
                int maxBleSpeed,
                double startBatteryVoltage,
                double endBatteryVoltage,
                int pointCount,
                TripAnalysisEngine.Result analysis
        ) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.distanceKm = distanceKm;
            this.maxBleSpeed = maxBleSpeed;
            this.startBatteryVoltage = startBatteryVoltage;
            this.endBatteryVoltage = endBatteryVoltage;
            this.pointCount = pointCount;
            this.analysis = analysis;
        }
    }

    private static final long AUTO_STOP_MS = 120_000L;

    private final Context context;
    private final Listener listener;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private final List<TripPoint> points = new ArrayList<>();

    private boolean monitoring = false;
    private boolean active = false;
    private boolean manualTrip = false;

    private long startTime = 0;
    private long endTime = 0;
    private long lastMovementTime = 0;

    private double distanceMeters = 0;
    private int maxBleSpeed = 0;
    private double startBatteryVoltage = 0;
    private double endBatteryVoltage = 0;
    private double reserveVoltage = 44.0;

    private G10BleManager.Telemetry telemetry;
    private Location previousTripLocation;
    private TripAnalysisEngine.Result lastAnalysis;

    private final SimpleDateFormat historyFormat =
            new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

    public TripTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        prefs = context.getSharedPreferences("g10_trips", Context.MODE_PRIVATE);
    }

    public void setReserveVoltage(double reserveVoltage) {
        if (reserveVoltage >= 30 && reserveVoltage <= 80) {
            this.reserveVoltage = reserveVoltage;
        }
    }

    public void startMonitoring() {
        if (monitoring || locationManager == null) return;

        boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!coarse && !fine) return;

        try {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1f,
                        locationListener
                );
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2500L,
                        2f,
                        locationListener
                );
            }
            monitoring = true;
        } catch (SecurityException ignored) {
        }
    }

    public void stopMonitoring() {
        if (!monitoring || locationManager == null) return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
        }
        monitoring = false;
    }

    private final LocationListener locationListener = this::handleLocation;

    private void handleLocation(Location location) {
        if (location == null) return;

        TripPoint point = new TripPoint(
                location.getTime() > 0 ? location.getTime() : System.currentTimeMillis(),
                location.getLatitude(),
                location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : 0.0,
                location.hasAccuracy() ? location.getAccuracy() : 999f,
                location.hasSpeed() ? location.getSpeed() * 3.6f : 0f,
                telemetry != null ? telemetry.speedKmh : 0,
                telemetry != null ? telemetry.batteryVoltage : 0,
                telemetry != null ? telemetry.modeLabel : "—",
                telemetry != null && telemetry.cruiseActive,
                telemetry != null && telemetry.brake
        );

        if (active && (point.accuracy <= 60f || point.accuracy == 999f)) {
            if (previousTripLocation != null) {
                float d = previousTripLocation.distanceTo(location);
                if (d >= 0 && d < 250) {
                    distanceMeters += d;
                }
            }
            previousTripLocation = new Location(location);
            points.add(point);
        }

        listener.onLocationChanged(point);
        listener.onTripStateChanged();
    }

    public void onTelemetry(G10BleManager.Telemetry t) {
        telemetry = t;
        endBatteryVoltage = t.batteryVoltage;
        if (t.speedKmh > maxBleSpeed) maxBleSpeed = t.speedKmh;

        if (t.speedKmh > 0 || t.moving) {
            lastMovementTime = System.currentTimeMillis();
            handler.removeCallbacks(autoStopRunnable);

            if (!active) {
                startTrip(false);
            }
        } else if (active && !manualTrip) {
            handler.removeCallbacks(autoStopRunnable);
            handler.postDelayed(autoStopRunnable, AUTO_STOP_MS);
        }

        listener.onTripStateChanged();
    }

    private final Runnable autoStopRunnable = () -> {
        if (active && !manualTrip) {
            long idle = System.currentTimeMillis() - lastMovementTime;
            if (idle >= AUTO_STOP_MS) {
                stopTrip("auto_idle");
            }
        }
    };

    public void startTrip(boolean manual) {
        if (active) {
            if (manual) manualTrip = true;
            listener.onTripStateChanged();
            return;
        }

        active = true;
        manualTrip = manual;
        startTime = System.currentTimeMillis();
        endTime = 0;
        lastMovementTime = startTime;
        distanceMeters = 0;
        maxBleSpeed = telemetry != null ? telemetry.speedKmh : 0;
        startBatteryVoltage = telemetry != null ? telemetry.batteryVoltage : 0;
        endBatteryVoltage = startBatteryVoltage;
        lastAnalysis = null;
        points.clear();
        previousTripLocation = null;

        startMonitoring();
        listener.onTripStateChanged();
    }

    public void stopTrip(String reason) {
        if (!active) return;

        active = false;
        manualTrip = false;
        endTime = System.currentTimeMillis();
        handler.removeCallbacks(autoStopRunnable);

        lastAnalysis = TripAnalysisEngine.analyze(toAnalysisSamples(), reserveVoltage);

        TripSummary summary = new TripSummary(
                startTime,
                endTime,
                lastAnalysis.distanceKm,
                maxBleSpeed,
                startBatteryVoltage,
                endBatteryVoltage,
                points.size(),
                lastAnalysis
        );

        saveHistory(summary, reason);
        listener.onTripStateChanged();
        listener.onTripFinished(summary);
    }

    private void saveHistory(TripSummary s, String reason) {
        String line = String.format(
                Locale.US,
                "%s  %.2f км  max %d  %.2f→%.2f В  %s",
                historyFormat.format(new Date(s.startTime)),
                s.distanceKm,
                s.maxBleSpeed,
                s.startBatteryVoltage,
                s.endBatteryVoltage,
                reason
        );

        if (s.analysis != null && s.analysis.dominantMode != null) {
            line += String.format(
                    Locale.US,
                    "  %s  GPS %.0f%%",
                    s.analysis.dominantMode,
                    s.analysis.gpsQualityPercent
            );
        }

        String old = prefs.getString("history", "");
        String combined = line + "\n" + old;

        String[] lines = combined.split("\n");
        StringBuilder keep = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            if (!lines[i].trim().isEmpty()) {
                keep.append(lines[i]).append('\n');
            }
        }

        prefs.edit().putString("history", keep.toString().trim()).apply();
    }

    public List<String> getHistory() {
        String raw = prefs.getString("history", "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    public List<TripPoint> getPointsSnapshot() {
        return new ArrayList<>(points);
    }

    public boolean isTripActive() {
        return active;
    }

    public boolean isManualTrip() {
        return manualTrip;
    }

    public double getDistanceKm() {
        return distanceMeters / 1000.0;
    }

    public long getDurationSeconds() {
        if (startTime == 0) return 0;
        long end = active ? System.currentTimeMillis() : (endTime > 0 ? endTime : startTime);
        return Math.max(0, (end - startTime) / 1000L);
    }

    public int getMaxBleSpeed() {
        return maxBleSpeed;
    }

    public int getPointCount() {
        return points.size();
    }

    public boolean hasTripData() {
        return startTime > 0;
    }

    public TripAnalysisEngine.Result getLastAnalysis() {
        return lastAnalysis;
    }

    private List<TripAnalysisEngine.Sample> toAnalysisSamples() {
        List<TripAnalysisEngine.Sample> samples = new ArrayList<>();
        for (TripPoint p : points) {
            samples.add(new TripAnalysisEngine.Sample(
                    p.timestamp,
                    p.latitude,
                    p.longitude,
                    p.altitude,
                    p.accuracy,
                    p.gpsSpeedKmh,
                    p.bleSpeedKmh,
                    p.batteryVoltage,
                    p.mode
            ));
        }
        return samples;
    }

    public String getTripCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("time_ms,lat,lon,altitude_m,accuracy_m,gps_speed_kmh,ble_speed_kmh,battery_v,mode,cruise_active,brake\n");

        for (TripPoint p : points) {
            sb.append(p.timestamp).append(',')
                    .append(String.format(Locale.US, "%.7f", p.latitude)).append(',')
                    .append(String.format(Locale.US, "%.7f", p.longitude)).append(',')
                    .append(String.format(Locale.US, "%.2f", p.altitude)).append(',')
                    .append(String.format(Locale.US, "%.1f", p.accuracy)).append(',')
                    .append(String.format(Locale.US, "%.2f", p.gpsSpeedKmh)).append(',')
                    .append(p.bleSpeedKmh).append(',')
                    .append(String.format(Locale.US, "%.2f", p.batteryVoltage)).append(',')
                    .append(csv(p.mode)).append(',')
                    .append(p.cruise).append(',')
                    .append(p.brake).append('\n');
        }

        return sb.toString();
    }

    public String getTripGpx() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"G10 Companion\" ")
                .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:g10=\"https://g10-companion.local/schema/1\">\n")
                .append("  <metadata><name>G10 trip ")
                .append(xml(isoTime(startTime)))
                .append("</name></metadata>\n")
                .append("  <trk><name>YADEA G10</name><trkseg>\n");

        for (TripPoint p : points) {
            sb.append(String.format(
                    Locale.US,
                    "    <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.2f</ele><time>%s</time>",
                    p.latitude,
                    p.longitude,
                    p.altitude,
                    xml(isoTime(p.timestamp))
            ));
            sb.append("<extensions>")
                    .append("<g10:gpsSpeedKmh>")
                    .append(String.format(Locale.US, "%.2f", p.gpsSpeedKmh))
                    .append("</g10:gpsSpeedKmh>")
                    .append("<g10:bleSpeedKmh>").append(p.bleSpeedKmh)
                    .append("</g10:bleSpeedKmh>")
                    .append("<g10:batteryV>")
                    .append(String.format(Locale.US, "%.2f", p.batteryVoltage))
                    .append("</g10:batteryV>")
                    .append("<g10:mode>").append(xml(p.mode)).append("</g10:mode>")
                    .append("</extensions></trkpt>\n");
        }

        sb.append("  </trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    public String getTripKml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
                .append("  <name>YADEA G10 — ")
                .append(xml(isoTime(startTime)))
                .append("</name>\n")
                .append("  <Style id=\"g10track\"><LineStyle><color>ffffa500</color>")
                .append("<width>5</width></LineStyle></Style>\n")
                .append("  <Placemark><name>Маршрут G10</name><styleUrl>#g10track</styleUrl>")
                .append("<LineString><tessellate>1</tessellate><altitudeMode>absolute</altitudeMode>")
                .append("<coordinates>\n");

        for (TripPoint p : points) {
            sb.append(String.format(
                    Locale.US,
                    "%.7f,%.7f,%.2f\n",
                    p.longitude,
                    p.latitude,
                    p.altitude
            ));
        }

        sb.append("</coordinates></LineString></Placemark>\n")
                .append("</Document></kml>\n");
        return sb.toString();
    }

    public String getAnalysisReport() {
        if (lastAnalysis == null) {
            return "Анализ ещё не сформирован. Завершите поездку кнопкой ФИНИШ.";
        }
        return lastAnalysis.buildRussianReport();
    }

    private String isoTime(long timeMs) {
        SimpleDateFormat iso = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US
        );
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return iso.format(new Date(Math.max(0, timeMs)));
    }

    private String xml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
