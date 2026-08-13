package com.g10blelab.app;

import java.util.ArrayList;
import java.util.List;

public final class TripAnalysisEngineTest {

    public static void main(String[] args) {
        analysesNormalRide();
        rejectsGpsTeleport();
        handlesEmptyRide();
        System.out.println("TripAnalysisEngineTest: OK");
    }

    private static void analysesNormalRide() {
        List<TripAnalysisEngine.Sample> points = new ArrayList<>();
        long t = 1_700_000_000_000L;
        double lat = 56.8300000;
        double lon = 60.6000000;

        points.add(sample(t, lat, lon, 250, 5, 0, 0, 52.0, "SPORT"));
        points.add(sample(t + 5_000, lat, lon, 250, 5, 0, 0, 52.0, "SPORT"));

        for (int i = 1; i <= 6; i++) {
            points.add(sample(
                    t + 5_000 + i * 10_000L,
                    lat + i * 0.00045,
                    lon,
                    250 + i * 2,
                    5,
                    18,
                    19,
                    52.0 - i * 0.18,
                    "SPORT"
            ));
        }

        points.add(sample(
                t + 75_000,
                lat + 6 * 0.00045,
                lon,
                262,
                5,
                0,
                0,
                51.25,
                "SPORT"
        ));

        TripAnalysisEngine.Result result = TripAnalysisEngine.analyze(points, 44.0);

        check(result.distanceKm > 0.25 && result.distanceKm < 0.35, "distance");
        check(result.averageMovingSpeedKmh > 12, "moving average");
        check("SPORT".equals(result.dominantMode), "dominant mode");
        check(result.gpsQualityPercent == 100.0, "gps quality");
        check(result.peakSagVoltage > 0.9, "sag");
        check(result.recoveryVoltage > 0.2, "recovery");
        check(result.elevationGainM >= 8, "elevation");
        check(result.buildRussianReport().contains("АНАЛИЗ ПОЕЗДКИ"), "report");
    }

    private static void rejectsGpsTeleport() {
        long t = 1_700_000_000_000L;
        List<TripAnalysisEngine.Sample> points = new ArrayList<>();
        points.add(sample(t, 56.83, 60.60, 250, 4, 10, 10, 50, "ECO"));
        points.add(sample(t + 1_000, 57.83, 61.60, 250, 4, 10, 10, 49.9, "ECO"));

        TripAnalysisEngine.Result result = TripAnalysisEngine.analyze(points, 44.0);
        check(result.distanceKm == 0, "teleport distance rejected");
        check(result.rejectedSegments == 1, "teleport segment rejected");
    }

    private static void handlesEmptyRide() {
        TripAnalysisEngine.Result result = TripAnalysisEngine.analyze(new ArrayList<>(), 44.0);
        check(result.sampleCount == 0, "empty sample count");
        check(!result.warnings.isEmpty(), "empty warning");
    }

    private static TripAnalysisEngine.Sample sample(
            long time,
            double lat,
            double lon,
            double alt,
            float accuracy,
            float gpsSpeed,
            int bleSpeed,
            double voltage,
            String mode
    ) {
        return new TripAnalysisEngine.Sample(
                time, lat, lon, alt, accuracy, gpsSpeed, bleSpeed, voltage, mode
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
