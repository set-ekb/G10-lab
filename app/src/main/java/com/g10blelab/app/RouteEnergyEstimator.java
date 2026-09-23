package com.g10blelab.app;

import java.util.Locale;

/**
 * Pure-Java route energy forecast for the YADEA G10.
 *
 * <p>The estimator has two data paths. Once enough personal trips exist it
 * uses the learned km/V value. Before that it deliberately returns a wider
 * interval based on the official 31 mile G10 range instead of pretending
 * that a precise BMS state-of-charge is available.</p>
 */
public final class RouteEnergyEstimator {

    /**
     * Official G10 specification: 31 miles on a full 48 V / 15.6 Ah pack.
     * Source: https://store.yadea.com/products/1200w-cost-effective-all-terrain-escooter-electric-g10
     */
    public static final double OFFICIAL_FULL_RANGE_KM = 31.0 * 1.609344;

    public static final String PROFILE_ECO = "ECO";
    public static final String PROFILE_BALANCED = "BALANCED";
    public static final String PROFILE_FAST = "FAST";

    public enum Status {
        SAFE,
        TIGHT,
        INSUFFICIENT,
        NO_ROUTE,
        NO_DATA
    }

    public static final class Input {
        public final double oneWayDistanceKm;
        public final boolean roundTrip;
        public final String profile;

        public final double currentVoltage;
        public final double fullVoltage;
        public final double reserveVoltage;
        public final double temperatureC;
        public final double riderAndCargoKg;
        public final double elevationGainM;
        public final double learnedKmPerVolt;
        public final double learnedProfileKmPerVolt;
        public final int learningTrips;

        public Input(
                double oneWayDistanceKm,
                boolean roundTrip,
                String profile,
                double currentVoltage,
                double fullVoltage,
                double reserveVoltage,
                double temperatureC,
                double riderAndCargoKg,
                double elevationGainM,
                double learnedKmPerVolt,
                double learnedProfileKmPerVolt,
                int learningTrips
        ) {
            this.oneWayDistanceKm = oneWayDistanceKm;
            this.roundTrip = roundTrip;
            this.profile = normalizeProfile(profile);
            this.currentVoltage = currentVoltage;
            this.fullVoltage = fullVoltage;
            this.reserveVoltage = reserveVoltage;
            this.temperatureC = temperatureC;
            this.riderAndCargoKg = riderAndCargoKg;
            this.elevationGainM = elevationGainM;
            this.learnedKmPerVolt = learnedKmPerVolt;
            this.learnedProfileKmPerVolt = learnedProfileKmPerVolt;
            this.learningTrips = Math.max(0, learningTrips);
        }
    }

    public static final class Result {
        public final Status status;
        public final double totalDistanceKm;
        public final double safeRangeKm;
        public final double expectedRangeKm;
        public final double optimisticRangeKm;
        public final double currentSocPercent;
        public final double arrivalSocLowPercent;
        public final double arrivalSocExpectedPercent;
        public final double arrivalSocHighPercent;
        public final double arrivalVoltageExpected;
        public final double minimumStartSocPercent;
        public final int confidencePercent;
        public final boolean personalized;
        public final String profile;

        public boolean hasBatteryData() { return currentSocPercent >= 0; }

        public double arrivalRangeKm() {
            return Math.max(0, expectedRangeKm - totalDistanceKm);
        }

        public double arrivalSafeRangeKm() {
            return Math.max(0, safeRangeKm - totalDistanceKm);
        }

        public double arrivalOptimisticRangeKm() {
            return Math.max(0, optimisticRangeKm - totalDistanceKm);
        }

        public double shortfallKm() {
            return Math.max(0, totalDistanceKm - expectedRangeKm);
        }

        private Result(
                Status status,
                double totalDistanceKm,
                double safeRangeKm,
                double expectedRangeKm,
                double optimisticRangeKm,
                double currentSocPercent,
                double arrivalSocLowPercent,
                double arrivalSocExpectedPercent,
                double arrivalSocHighPercent,
                double arrivalVoltageExpected,
                double minimumStartSocPercent,
                int confidencePercent,
                boolean personalized,
                String profile
        ) {
            this.status = status;
            this.totalDistanceKm = totalDistanceKm;
            this.safeRangeKm = safeRangeKm;
            this.expectedRangeKm = expectedRangeKm;
            this.optimisticRangeKm = optimisticRangeKm;
            this.currentSocPercent = currentSocPercent;
            this.arrivalSocLowPercent = arrivalSocLowPercent;
            this.arrivalSocExpectedPercent = arrivalSocExpectedPercent;
            this.arrivalSocHighPercent = arrivalSocHighPercent;
            this.arrivalVoltageExpected = arrivalVoltageExpected;
            this.minimumStartSocPercent = minimumStartSocPercent;
            this.confidencePercent = confidencePercent;
            this.personalized = personalized;
            this.profile = profile;
        }

        public String headlineRussian() {
            switch (status) {
                case SAFE:
                    return "МОЖНО ЕХАТЬ";
                case TIGHT:
                    return "НА ГРАНИЦЕ ЗАПАСА";
                case INSUFFICIENT:
                    return "ПО ПРОГНОЗУ НЕ ХВАТИТ";
                case NO_ROUTE:
                    return "ВЫБЕРИТЕ МАРШРУТ";
                default:
                    return "НУЖНЫ ДАННЫЕ БАТАРЕИ";
            }
        }

        public String detailsRussian() {
            if (status == Status.NO_DATA) {
                return "Подключите G10 и дождитесь напряжения батареи.";
            }

            String arrival = shortfallKm() > 0
                    ? String.format(Locale.US, "не хватает примерно %.1f км", shortfallKm())
                    : String.format(Locale.US, "после маршрута ~%.1f км (%.1f–%.1f км)",
                            arrivalRangeKm(), arrivalSafeRangeKm(), arrivalOptimisticRangeKm());

            String basis = personalized
                    ? "по вашим поездкам"
                    : "предварительно по заводским данным";

            String extra = status == Status.INSUFFICIENT
                    ? " Сократите путь или зарядите батарею."
                    : "";

            return String.format(
                    Locale.US,
                    "Маршрут %.1f км • %s • с запасом сейчас %.1f км • %s. Оценка по напряжению.%s",
                    totalDistanceKm,
                    arrival,
                    safeRangeKm,
                    basis,
                    extra
            );
        }
    }

    private RouteEnergyEstimator() {
    }

    public static Result estimate(Input input) {
        if (input == null) return noData(PROFILE_BALANCED);

        if (!validInput(input)) return noData(input.profile);
        double distance = input.oneWayDistanceKm;
        if (input.roundTrip) distance *= 2.0;

        if (input.currentVoltage <= 0 || input.fullVoltage <= input.reserveVoltage) {
            return noData(input.profile, distance);
        }

        double soc = estimateSocPercent(
                input.currentVoltage,
                input.fullVoltage,
                input.reserveVoltage
        );
        double socFraction = soc / 100.0;

        boolean hasProfileLearning = input.learningTrips >= 3 && input.learnedProfileKmPerVolt > 0;
        boolean hasAnyLearning = input.learningTrips >= 3 && (hasProfileLearning || input.learnedKmPerVolt > 0);

        double baseRange;
        if (hasAnyLearning) {
            double rate = hasProfileLearning
                    ? input.learnedProfileKmPerVolt
                    : input.learnedKmPerVolt * learnedProfileFactor(input.profile);
            baseRange = Math.max(0, input.currentVoltage - input.reserveVoltage) * rate;
        } else {
            baseRange = OFFICIAL_FULL_RANGE_KM * socFraction * officialProfileFactor(input.profile);
        }

        double routeFactor = temperatureFactor(input.temperatureC)
                * loadFactor(input.riderAndCargoKg)
                * elevationFactor(distance, input.elevationGainM * (input.roundTrip ? 2 : 1));
        double expectedRange = Math.max(0, baseRange * routeFactor);

        double uncertainty;
        if (hasProfileLearning && input.learningTrips >= 5) {
            uncertainty = 0.10;
        } else if (hasAnyLearning && input.learningTrips >= 3) {
            uncertainty = 0.15;
        } else if (hasAnyLearning) {
            uncertainty = 0.20;
        } else {
            uncertainty = 0.27;
        }

        double safeRange = expectedRange * (1.0 - uncertainty);
        double optimisticRange = expectedRange * (1.0 + uncertainty * 0.65);

        double arrivalLow = arrivalSoc(soc, distance, safeRange);
        double arrivalExpected = arrivalSoc(soc, distance, expectedRange);
        double arrivalHigh = arrivalSoc(soc, distance, optimisticRange);

        double usableAfter = expectedRange > 0
                ? clamp(1.0 - distance / expectedRange, 0, 1)
                : 0;
        double arrivalVoltage = input.reserveVoltage
                + Math.max(0, input.currentVoltage - input.reserveVoltage) * usableAfter;

        Status status;
        if (distance <= 0) status = Status.NO_ROUTE;
        else if (distance <= safeRange) status = Status.SAFE;
        else if (distance <= expectedRange) status = Status.TIGHT;
        else status = Status.INSUFFICIENT;

        double fullRange = socFraction > 0.03
                ? expectedRange / socFraction
                : expectedRange;
        double requiredSoc = fullRange > 0
                ? clamp(distance / fullRange * 100.0 * 1.12, 0, 100)
                : 100;

        int confidence = hasProfileLearning
                ? 45 + Math.min(45, input.learningTrips * 7)
                : hasAnyLearning
                        ? 35 + Math.min(40, input.learningTrips * 6)
                        : 24;
        confidence = (int) Math.round(clamp(confidence, 10, 95));

        return new Result(
                status,
                distance,
                safeRange,
                expectedRange,
                optimisticRange,
                soc,
                arrivalLow,
                arrivalExpected,
                arrivalHigh,
                arrivalVoltage,
                requiredSoc,
                confidence,
                hasAnyLearning,
                input.profile
        );
    }

    public static double estimateSocPercent(
            double currentVoltage,
            double fullVoltage,
            double reserveVoltage
    ) {
        if (!Double.isFinite(currentVoltage) || !Double.isFinite(fullVoltage) ||
                !Double.isFinite(reserveVoltage) || currentVoltage <= 0 ||
                reserveVoltage <= 0 || fullVoltage <= reserveVoltage) return -1;
        double linear = clamp(
                (currentVoltage - reserveVoltage) / (fullVoltage - reserveVoltage),
                0,
                1
        );
        double curved = linear * linear * (3.0 - 2.0 * linear);
        return curved * 100.0;
    }

    public static String normalizeProfile(String profile) {
        if (profile == null) return PROFILE_BALANCED;
        String normalized = profile.trim().toUpperCase(Locale.ROOT);
        if (PROFILE_ECO.equals(normalized) || PROFILE_FAST.equals(normalized)) {
            return normalized;
        }
        return PROFILE_BALANCED;
    }

    private static boolean validInput(Input i) {
        return Double.isFinite(i.oneWayDistanceKm) && i.oneWayDistanceKm >= 0 && i.oneWayDistanceKm <= 500 &&
                Double.isFinite(i.currentVoltage) && Double.isFinite(i.fullVoltage) &&
                Double.isFinite(i.reserveVoltage) && i.reserveVoltage > 0 &&
                Double.isFinite(i.temperatureC) && i.temperatureC >= -40 && i.temperatureC <= 70 &&
                Double.isFinite(i.riderAndCargoKg) && i.riderAndCargoKg >= 20 && i.riderAndCargoKg <= 200 &&
                Double.isFinite(i.elevationGainM) && i.elevationGainM >= 0 && i.elevationGainM <= 10000 &&
                Double.isFinite(i.learnedKmPerVolt) && Double.isFinite(i.learnedProfileKmPerVolt);
    }

    private static Result noData(String profile) {
        return noData(profile, 0);
    }

    private static Result noData(String profile, double distance) {
        return new Result(
                Status.NO_DATA,
                distance,
                0,
                0,
                0,
                -1,
                0,
                0,
                0,
                0,
                100,
                0,
                false,
                normalizeProfile(profile)
        );
    }

    private static double officialProfileFactor(String profile) {
        if (PROFILE_ECO.equals(profile)) return 1.00;
        if (PROFILE_FAST.equals(profile)) return 0.70;
        return 0.84;
    }

    private static double learnedProfileFactor(String profile) {
        if (PROFILE_ECO.equals(profile)) return 1.12;
        if (PROFILE_FAST.equals(profile)) return 0.84;
        return 1.00;
    }

    private static double temperatureFactor(double celsius) {
        if (celsius < -10) return 0.65;
        if (celsius < 0) return 0.75;
        if (celsius < 10) return 0.85;
        if (celsius < 18) return 0.93;
        if (celsius <= 32) return 1.00;
        if (celsius <= 42) return 0.95;
        return 0.88;
    }

    private static double loadFactor(double kg) {
        double safeKg = kg > 0 ? kg : 75.0;
        return clamp(1.0 - (safeKg - 75.0) * 0.004, 0.72, 1.06);
    }

    private static double elevationFactor(double distanceKm, double gainM) {
        if (distanceKm <= 0 || gainM <= 0) return 1.0;
        double climbPerKm = gainM / distanceKm;
        return clamp(1.0 / (1.0 + climbPerKm / 120.0), 0.65, 1.0);
    }

    private static double arrivalSoc(double currentSoc, double distance, double availableRange) {
        if (availableRange <= 0) return 0;
        return clamp(currentSoc * (1.0 - distance / availableRange), 0, currentSoc);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
