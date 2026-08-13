package com.g10blelab.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Local, explainable battery model. It deliberately avoids claiming real
 * current, Wh/km or laboratory SOH while those values are absent from BLE.
 */
public class BatteryCoach {

    private static final double DEFAULT_FULL_VOLTAGE = 54.6;
    private static final double DEFAULT_RESERVE_VOLTAGE = 44.0;
    private static final double DEFAULT_TEMPERATURE_C = 20.0;

    private final SharedPreferences prefs;

    private double currentVoltage = 0;
    private double restingVoltage = 0;
    private double currentSag = -1;
    private double minimumMovingVoltage = 0;
    private double latestRecoveryVoltage = 0;
    private boolean wasMoving = false;
    private String currentMode = "UNKNOWN";

    public BatteryCoach(Context context) {
        prefs = context.getSharedPreferences("g10_battery_ai", Context.MODE_PRIVATE);
    }

    public void updateTelemetry(int speedKmh, double voltage, String mode) {
        if (voltage <= 0 || voltage > 80) return;

        currentVoltage = voltage;
        currentMode = TripAnalysisEngine.normalizeMode(mode);

        if (speedKmh <= 0) {
            if (restingVoltage <= 0) {
                restingVoltage = voltage;
            } else {
                restingVoltage = restingVoltage * 0.85 + voltage * 0.15;
            }

            if (wasMoving && minimumMovingVoltage > 0) {
                latestRecoveryVoltage = Math.max(0, voltage - minimumMovingVoltage);
            }
            currentSag = 0;
            wasMoving = false;
        } else {
            if (!wasMoving || minimumMovingVoltage <= 0) {
                minimumMovingVoltage = voltage;
            } else {
                minimumMovingVoltage = Math.min(minimumMovingVoltage, voltage);
            }
            currentSag = restingVoltage > 0
                    ? Math.max(0, restingVoltage - voltage)
                    : -1;
            wasMoving = true;
        }
    }

    public void observeTrip(TripAnalysisEngine.Result result) {
        if (result == null) return;

        prefs.edit()
                .putFloat("last_sag_v", (float) result.peakSagVoltage)
                .putFloat("last_recovery_v", (float) result.recoveryVoltage)
                .putFloat("last_quality", (float) result.gpsQualityPercent)
                .putString("last_trip_report", result.buildRussianReport())
                .apply();

        double drop = result.voltageDrop;
        double sample = result.kmPerVolt;
        if (result.distanceKm < 0.20 || drop < 0.15 || drop > 8.0 ||
                sample < 0.2 || sample > 80.0 || result.gpsQualityPercent < 45) {
            return;
        }

        int oldCount = prefs.getInt("learning_trips", 0);
        double alpha = clamp(0.15 + result.gpsQualityPercent / 500.0, 0.15, 0.35);
        double learned = learn("km_per_volt", sample, alpha);

        String dominant = TripAnalysisEngine.normalizeMode(result.dominantMode);
        if (!"UNKNOWN".equals(dominant) && result.modeSharePercent(dominant) >= 65) {
            learn("km_per_volt_" + dominant.toLowerCase(Locale.ROOT), sample, alpha);
            prefs.edit()
                    .putInt("trips_" + dominant.toLowerCase(Locale.ROOT),
                            prefs.getInt("trips_" + dominant.toLowerCase(Locale.ROOT), 0) + 1)
                    .apply();
        }

        int newCount = oldCount + 1;
        double baseline = prefs.getFloat("baseline_km_per_volt", 0f);
        if (newCount >= 3 && (baseline <= 0 || learned > baseline)) {
            baseline = learned;
        }

        SharedPreferences.Editor edit = prefs.edit()
                .putInt("learning_trips", newCount)
                .putFloat("last_efficiency", (float) sample);
        if (baseline > 0) edit.putFloat("baseline_km_per_volt", (float) baseline);
        edit.apply();
    }

    private double learn(String key, double sample, double alpha) {
        double old = prefs.getFloat(key, 0f);
        double learned = old > 0
                ? old * (1.0 - alpha) + sample * alpha
                : sample;
        prefs.edit().putFloat(key, (float) learned).apply();
        return learned;
    }

    public boolean updateProfile(
            double fullVoltage,
            double reserveVoltage,
            double capacityAh,
            double temperatureC,
            int cycleCount
    ) {
        if (fullVoltage < 40 || fullVoltage > 90 ||
                reserveVoltage < 30 || reserveVoltage >= fullVoltage - 3 ||
                capacityAh < 0 || capacityAh > 100 ||
                temperatureC < -40 || temperatureC > 70 ||
                cycleCount < 0 || cycleCount > 100_000) {
            return false;
        }

        prefs.edit()
                .putFloat("profile_full_v", (float) fullVoltage)
                .putFloat("profile_reserve_v", (float) reserveVoltage)
                .putFloat("profile_capacity_ah", (float) capacityAh)
                .putFloat("profile_temperature_c", (float) temperatureC)
                .putInt("profile_cycles", cycleCount)
                .apply();
        return true;
    }

    public boolean hasLearnedEfficiency() {
        return getKmPerVolt() > 0;
    }

    public double getKmPerVolt() {
        return prefs.getFloat("km_per_volt", 0f);
    }

    public double getModeKmPerVolt(String mode) {
        String normalized = TripAnalysisEngine.normalizeMode(mode);
        if ("UNKNOWN".equals(normalized)) return 0;
        return prefs.getFloat(
                "km_per_volt_" + normalized.toLowerCase(Locale.ROOT),
                0f
        );
    }

    public int getLearningTripCount() {
        return prefs.getInt("learning_trips", 0);
    }

    public double getForecastRangeKm() {
        double rate = getModeKmPerVolt(currentMode);
        if (rate <= 0) rate = getKmPerVolt();

        double reserve = getReserveVoltage();
        if (rate <= 0 || currentVoltage <= reserve) return -1;

        double usableVoltage = currentVoltage - reserve;
        return Math.max(0, usableVoltage * rate * temperatureFactor(getTemperatureC()));
    }

    public double getSocEstimatePercent() {
        double full = getFullVoltage();
        double reserve = getReserveVoltage();
        if (currentVoltage <= 0 || full <= reserve) return -1;

        double linear = clamp((currentVoltage - reserve) / (full - reserve), 0, 1);
        // A mild S-curve is closer to a resting Li-ion pack than a straight line,
        // but the value remains an estimate until the real BMS SOC is decoded.
        double curved = linear * linear * (3.0 - 2.0 * linear);
        return curved * 100.0;
    }

    public double getHealthTrendPercent() {
        if (getLearningTripCount() < 5) return -1;
        double baseline = prefs.getFloat("baseline_km_per_volt", 0f);
        double learned = getKmPerVolt();
        if (baseline <= 0 || learned <= 0) return -1;
        return clamp(learned / baseline * 100.0, 55, 105);
    }

    public int getConfidencePercent() {
        int trips = getLearningTripCount();
        if (trips <= 0) return 0;
        double quality = prefs.getFloat("last_quality", 50f);
        double confidence = Math.min(85, trips * 12.0) * clamp(quality / 80.0, 0.55, 1.0);
        if (getModeKmPerVolt(currentMode) > 0) confidence += 10;
        return (int) Math.round(clamp(confidence, 5, 95));
    }

    public String getVerdict() {
        double sag = prefs.getFloat("last_sag_v", 0f);
        double recovery = prefs.getFloat("last_recovery_v", 0f);
        double health = getHealthTrendPercent();

        if (sag >= 2.0 && recovery < sag * 0.35) {
            return "Повторить тест на тёплой батарее: высокая просадка и слабое восстановление.";
        }
        if (sag >= 2.0) {
            return "Просадка высокая, но напряжение восстанавливается. Сравните ещё 2–3 похожие поездки.";
        }
        if (health > 0 && health < 80) {
            return "Тренд запаса хода снизился относительно лучших поездок. Проверить давление шин и батарею.";
        }
        if (getLearningTripCount() < 3) {
            return "Идёт обучение. Для первого устойчивого прогноза нужны минимум 3 обычные поездки.";
        }
        return "По накопленным поездкам явного ухудшения батареи не видно.";
    }

    public String getLastTripReport() {
        return prefs.getString("last_trip_report", "Пока нет завершённой поездки для анализа.");
    }

    public double getCurrentVoltage() {
        return currentVoltage;
    }

    public double getCurrentSag() {
        return currentSag;
    }

    public double getLatestRecoveryVoltage() {
        return latestRecoveryVoltage;
    }

    public String getCurrentMode() {
        return currentMode;
    }

    public double getFullVoltage() {
        return prefs.getFloat("profile_full_v", (float) DEFAULT_FULL_VOLTAGE);
    }

    public double getReserveVoltage() {
        return prefs.getFloat("profile_reserve_v", (float) DEFAULT_RESERVE_VOLTAGE);
    }

    public double getCapacityAh() {
        return prefs.getFloat("profile_capacity_ah", 0f);
    }

    public double getTemperatureC() {
        return prefs.getFloat("profile_temperature_c", (float) DEFAULT_TEMPERATURE_C);
    }

    public int getCycleCount() {
        return prefs.getInt("profile_cycles", 0);
    }

    public double getNominalEnergyWh() {
        double capacity = getCapacityAh();
        if (capacity <= 0) return 0;
        return 48.0 * capacity;
    }

    private double temperatureFactor(double celsius) {
        if (celsius < -10) return 0.65;
        if (celsius < 0) return 0.75;
        if (celsius < 10) return 0.85;
        if (celsius < 18) return 0.93;
        if (celsius <= 32) return 1.0;
        if (celsius <= 42) return 0.95;
        return 0.88;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
