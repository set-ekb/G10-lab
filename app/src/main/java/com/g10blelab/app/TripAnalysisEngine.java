package com.g10blelab.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java trip analysis. Keeping the math independent from Android makes it
 * possible to run deterministic tests without a phone or emulator.
 */
public final class TripAnalysisEngine {

    private TripAnalysisEngine() {
    }

    public static final class Sample {
        public final long timestamp;
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final float accuracy;
        public final float gpsSpeedKmh;
        public final int bleSpeedKmh;
        public final double batteryVoltage;
        public final String mode;

        public Sample(
                long timestamp,
                double latitude,
                double longitude,
                double altitude,
                float accuracy,
                float gpsSpeedKmh,
                int bleSpeedKmh,
                double batteryVoltage,
                String mode
        ) {
            this.timestamp = timestamp;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracy = accuracy;
            this.gpsSpeedKmh = gpsSpeedKmh;
            this.bleSpeedKmh = bleSpeedKmh;
            this.batteryVoltage = batteryVoltage;
            this.mode = normalizeMode(mode);
        }

        public double bestSpeedKmh() {
            return Math.max(gpsSpeedKmh, bleSpeedKmh);
        }

        public boolean isMoving() {
            return bestSpeedKmh() >= 2.0;
        }
    }

    public static final class Result {
        public final int sampleCount;
        public final int acceptedSegments;
        public final int rejectedSegments;
        public final long durationSeconds;
        public final long movingSeconds;
        public final double distanceKm;
        public final double averageMovingSpeedKmh;
        public final double maxSpeedKmh;
        public final double elevationGainM;
        public final double elevationLossM;
        public final double startRestVoltage;
        public final double endRestVoltage;
        public final double minimumMovingVoltage;
        public final double voltageDrop;
        public final double peakSagVoltage;
        public final double recoveryVoltage;
        public final double recoveryPercent;
        public final double kmPerVolt;
        public final double gpsQualityPercent;
        public final double loadIndex;
        public final String dominantMode;
        public final Map<String, Double> modeDistanceKm;
        public final List<String> warnings;

        private Result(
                int sampleCount,
                int acceptedSegments,
                int rejectedSegments,
                long durationSeconds,
                long movingSeconds,
                double distanceKm,
                double averageMovingSpeedKmh,
                double maxSpeedKmh,
                double elevationGainM,
                double elevationLossM,
                double startRestVoltage,
                double endRestVoltage,
                double minimumMovingVoltage,
                double voltageDrop,
                double peakSagVoltage,
                double recoveryVoltage,
                double recoveryPercent,
                double kmPerVolt,
                double gpsQualityPercent,
                double loadIndex,
                String dominantMode,
                Map<String, Double> modeDistanceKm,
                List<String> warnings
        ) {
            this.sampleCount = sampleCount;
            this.acceptedSegments = acceptedSegments;
            this.rejectedSegments = rejectedSegments;
            this.durationSeconds = durationSeconds;
            this.movingSeconds = movingSeconds;
            this.distanceKm = distanceKm;
            this.averageMovingSpeedKmh = averageMovingSpeedKmh;
            this.maxSpeedKmh = maxSpeedKmh;
            this.elevationGainM = elevationGainM;
            this.elevationLossM = elevationLossM;
            this.startRestVoltage = startRestVoltage;
            this.endRestVoltage = endRestVoltage;
            this.minimumMovingVoltage = minimumMovingVoltage;
            this.voltageDrop = voltageDrop;
            this.peakSagVoltage = peakSagVoltage;
            this.recoveryVoltage = recoveryVoltage;
            this.recoveryPercent = recoveryPercent;
            this.kmPerVolt = kmPerVolt;
            this.gpsQualityPercent = gpsQualityPercent;
            this.loadIndex = loadIndex;
            this.dominantMode = dominantMode;
            this.modeDistanceKm = Collections.unmodifiableMap(modeDistanceKm);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public double modeSharePercent(String mode) {
            if (distanceKm <= 0) return 0;
            Double km = modeDistanceKm.get(normalizeMode(mode));
            return km == null ? 0 : clamp(km / distanceKm * 100.0, 0, 100);
        }

        public String buildRussianReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("АНАЛИЗ ПОЕЗДКИ\n");
            sb.append(String.format(
                    Locale.US,
                    "Дистанция %.2f км • движение %02d:%02d • средняя %.1f • максимум %.1f км/ч\n",
                    distanceKm,
                    movingSeconds / 60,
                    movingSeconds % 60,
                    averageMovingSpeedKmh,
                    maxSpeedKmh
            ));
            sb.append(String.format(
                    Locale.US,
                    "Рельеф: +%.0f / −%.0f м • нагрузка %.0f/100 • GPS %.0f%%\n",
                    elevationGainM,
                    elevationLossM,
                    loadIndex,
                    gpsQualityPercent
            ));

            if (startRestVoltage > 0 && endRestVoltage > 0) {
                sb.append(String.format(
                        Locale.US,
                        "Батарея: %.2f→%.2f В • минимум %.2f В • падение %.2f В\n",
                        startRestVoltage,
                        endRestVoltage,
                        minimumMovingVoltage,
                        voltageDrop
                ));
                sb.append(String.format(
                        Locale.US,
                        "Просадка %.2f В • восстановление %.2f В (%.0f%%)",
                        peakSagVoltage,
                        recoveryVoltage,
                        recoveryPercent
                ));
                if (kmPerVolt > 0) {
                    sb.append(String.format(Locale.US, " • %.2f км/В", kmPerVolt));
                }
                sb.append('\n');
            } else {
                sb.append("Батарея: недостаточно корректной телеметрии\n");
            }

            sb.append("Режимы: ")
                    .append(modeLine("ECO"))
                    .append(" • ")
                    .append(modeLine("SPORT"))
                    .append(" • ")
                    .append(modeLine("RACE"))
                    .append(" • основной ")
                    .append(dominantMode)
                    .append('\n');

            if (warnings.isEmpty()) {
                sb.append("Вердикт: явных отклонений по этой поездке не найдено.");
            } else {
                sb.append("Обратить внимание:\n");
                for (String warning : warnings) {
                    sb.append("• ").append(warning).append('\n');
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
            }
            return sb.toString();
        }

        private String modeLine(String mode) {
            return String.format(Locale.US, "%s %.0f%%", mode, modeSharePercent(mode));
        }
    }

    public static Result analyze(List<Sample> input, double reserveVoltage) {
        List<Sample> samples = input == null ? Collections.emptyList() : input;
        int size = samples.size();

        if (size == 0) {
            List<String> warnings = new ArrayList<>();
            warnings.add("нет GPS-точек для анализа");
            return new Result(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    "—", emptyModeMap(), warnings
            );
        }

        long durationSeconds = Math.max(
                0,
                (samples.get(size - 1).timestamp - samples.get(0).timestamp) / 1000L
        );

        int accepted = 0;
        int rejected = 0;
        long movingSeconds = 0;
        double distanceM = 0;
        double gain = 0;
        double loss = 0;
        double maxSpeed = 0;
        Map<String, Double> modeMeters = emptyModeMap();

        for (Sample sample : samples) {
            maxSpeed = Math.max(maxSpeed, sample.bestSpeedKmh());
        }

        for (int i = 1; i < size; i++) {
            Sample a = samples.get(i - 1);
            Sample b = samples.get(i);
            double dt = (b.timestamp - a.timestamp) / 1000.0;

            if (dt <= 0 || dt > 60 || !validLocation(a) || !validLocation(b)) {
                rejected++;
                continue;
            }

            double segmentM = haversineMeters(
                    a.latitude, a.longitude, b.latitude, b.longitude
            );
            double calculatedSpeed = segmentM / dt * 3.6;

            if (segmentM < 0 || segmentM > 250 || calculatedSpeed > 90) {
                rejected++;
                continue;
            }

            accepted++;
            distanceM += segmentM;
            String segmentMode = normalizeMode(a.mode);
            modeMeters.put(segmentMode, modeMeters.get(segmentMode) + segmentM);

            if (a.isMoving() || b.isMoving() || calculatedSpeed >= 2.0) {
                movingSeconds += Math.min(15, Math.max(1, Math.round(dt)));
            }

            if (a.accuracy <= 30 && b.accuracy <= 30) {
                double elevationDelta = b.altitude - a.altitude;
                if (Math.abs(elevationDelta) >= 1.5 && Math.abs(elevationDelta) <= 15) {
                    if (elevationDelta > 0) gain += elevationDelta;
                    else loss -= elevationDelta;
                }
            }
        }

        int firstMoving = firstMovingIndex(samples);
        int lastMoving = lastMovingIndex(samples);
        double firstVoltage = firstValidVoltage(samples);
        double lastVoltage = lastValidVoltage(samples);
        double startRest = averageRestVoltage(samples, 0, firstMoving, true);
        double endRest = averageRestVoltage(samples, lastMoving, size - 1, false);
        if (startRest <= 0) startRest = firstVoltage;
        if (endRest <= 0) endRest = lastVoltage;

        double minMoving = minimumMovingVoltage(samples);
        if (minMoving <= 0) minMoving = minimumValidVoltage(samples);
        if (minMoving <= 0) minMoving = Math.min(startRest, endRest);

        double rawDrop = startRest > 0 && endRest > 0 ? startRest - endRest : 0;
        double drop = Math.max(0, rawDrop);
        double sag = startRest > 0 && minMoving > 0
                ? Math.max(0, startRest - minMoving)
                : 0;
        double recovery = endRest > 0 && minMoving > 0
                ? Math.max(0, endRest - minMoving)
                : 0;
        double recoveryPercent = sag >= 0.05
                ? clamp(recovery / sag * 100.0, 0, 200)
                : 0;

        double distanceKm = distanceM / 1000.0;
        double averageMoving = movingSeconds > 0
                ? distanceKm / (movingSeconds / 3600.0)
                : 0;
        double kmPerVolt = drop >= 0.15 ? distanceKm / drop : 0;
        double quality = accepted + rejected > 0
                ? accepted * 100.0 / (accepted + rejected)
                : 0;

        Map<String, Double> modeKm = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : modeMeters.entrySet()) {
            modeKm.put(entry.getKey(), entry.getValue() / 1000.0);
        }
        String dominantMode = dominantMode(modeKm);

        double climbPerKm = distanceKm >= 0.1 ? gain / distanceKm : 0;
        double loadIndex = clamp(
                Math.min(40, averageMoving / 25.0 * 40.0) +
                        Math.min(20, maxSpeed / 45.0 * 20.0) +
                        Math.min(20, climbPerKm / 30.0 * 20.0) +
                        Math.min(20, sag / 3.0 * 20.0),
                0,
                100
        );

        List<String> warnings = new ArrayList<>();
        if (size < 10 || distanceKm < 0.20) {
            warnings.add("поездка слишком короткая для уверенного вывода");
        }
        if (quality < 60) {
            warnings.add("низкое качество GPS; часть дистанции могла быть отброшена");
        }
        if (rawDrop < -0.30) {
            warnings.add("конечное напряжение выше начального: батарея не успела отстояться");
        }
        if (sag >= 2.0) {
            warnings.add("высокая просадка под нагрузкой; сравнить на тёплой батарее и ровной дороге");
        } else if (sag >= 1.2) {
            warnings.add("заметная просадка под нагрузкой; наблюдать динамику в похожих поездках");
        }
        if (sag >= 0.8 && recoveryPercent > 0 && recoveryPercent < 35) {
            warnings.add("слабое восстановление напряжения после нагрузки");
        }
        if (reserveVoltage > 0 && minMoving > 0 && minMoving <= reserveVoltage + 0.5) {
            warnings.add("под нагрузкой достигнут резервный порог батареи");
        }

        return new Result(
                size,
                accepted,
                rejected,
                durationSeconds,
                movingSeconds,
                distanceKm,
                averageMoving,
                maxSpeed,
                gain,
                loss,
                startRest,
                endRest,
                minMoving,
                drop,
                sag,
                recovery,
                recoveryPercent,
                kmPerVolt,
                quality,
                loadIndex,
                dominantMode,
                modeKm,
                warnings
        );
    }

    private static Map<String, Double> emptyModeMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("ECO", 0.0);
        map.put("SPORT", 0.0);
        map.put("RACE", 0.0);
        map.put("UNKNOWN", 0.0);
        return map;
    }

    private static String dominantMode(Map<String, Double> modeKm) {
        String bestMode = "—";
        double best = 0;
        for (String mode : new String[]{"ECO", "SPORT", "RACE", "UNKNOWN"}) {
            double value = modeKm.containsKey(mode) ? modeKm.get(mode) : 0;
            if (value > best) {
                best = value;
                bestMode = "UNKNOWN".equals(mode) ? "НЕ ОПРЕДЕЛЁН" : mode;
            }
        }
        return bestMode;
    }

    private static int firstMovingIndex(List<Sample> samples) {
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).isMoving()) return i;
        }
        return Math.max(0, samples.size() - 1);
    }

    private static int lastMovingIndex(List<Sample> samples) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            if (samples.get(i).isMoving()) return i;
        }
        return 0;
    }

    private static double averageRestVoltage(
            List<Sample> samples,
            int from,
            int to,
            boolean forward
    ) {
        if (samples.isEmpty()) return 0;
        from = Math.max(0, Math.min(from, samples.size() - 1));
        to = Math.max(0, Math.min(to, samples.size() - 1));
        int count = 0;
        double sum = 0;

        if (forward) {
            for (int i = from; i <= to && count < 5; i++) {
                Sample sample = samples.get(i);
                if (!sample.isMoving() && validVoltage(sample.batteryVoltage)) {
                    sum += sample.batteryVoltage;
                    count++;
                }
            }
        } else {
            for (int i = to; i >= from && count < 5; i--) {
                Sample sample = samples.get(i);
                if (!sample.isMoving() && validVoltage(sample.batteryVoltage)) {
                    sum += sample.batteryVoltage;
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private static double firstValidVoltage(List<Sample> samples) {
        for (Sample sample : samples) {
            if (validVoltage(sample.batteryVoltage)) return sample.batteryVoltage;
        }
        return 0;
    }

    private static double lastValidVoltage(List<Sample> samples) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            double voltage = samples.get(i).batteryVoltage;
            if (validVoltage(voltage)) return voltage;
        }
        return 0;
    }

    private static double minimumMovingVoltage(List<Sample> samples) {
        double min = Double.MAX_VALUE;
        for (Sample sample : samples) {
            if (sample.isMoving() && validVoltage(sample.batteryVoltage)) {
                min = Math.min(min, sample.batteryVoltage);
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    private static double minimumValidVoltage(List<Sample> samples) {
        double min = Double.MAX_VALUE;
        for (Sample sample : samples) {
            if (validVoltage(sample.batteryVoltage)) {
                min = Math.min(min, sample.batteryVoltage);
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    private static boolean validVoltage(double voltage) {
        return voltage >= 20 && voltage <= 80;
    }

    private static boolean validLocation(Sample sample) {
        return sample.accuracy > 0 && sample.accuracy <= 60 &&
                sample.latitude >= -90 && sample.latitude <= 90 &&
                sample.longitude >= -180 && sample.longitude <= 180;
    }

    static String normalizeMode(String mode) {
        if (mode == null) return "UNKNOWN";
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("ECO")) return "ECO";
        if (upper.contains("SPORT")) return "SPORT";
        if (upper.contains("RACE")) return "RACE";
        return "UNKNOWN";
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
