package com.g10blelab.app;

public final class RouteEnergyEstimatorTest {

    public static void main(String[] args) {
        forecastsSafeRouteFromOfficialData();
        accountsForRoundTripAndFastMode();
        penalizesColdHeavyClimb();
        prefersPersonalLearning();
        rejectsForecastWithoutTelemetry();
        handlesReserveWithoutLosingTelemetry();
        rejectsNonFiniteInputs();
        keepsRangeWithoutDestination();
        reportsArrivalAndShortfallInKm();
        requiresThreeTripsForPersonalization();
        preservesClimbDensityOnReturn();
        System.out.println("RouteEnergyEstimatorTest: OK");
    }

    private static void forecastsSafeRouteFromOfficialData() {
        RouteEnergyEstimator.Result result = RouteEnergyEstimator.estimate(
                input(8, false, "ECO", 52.0, 20, 75, 0, 0, 0, 0)
        );

        check(result.status == RouteEnergyEstimator.Status.SAFE, "safe route");
        check(result.expectedRangeKm > result.safeRangeKm, "range interval");
        check(result.arrivalSocExpectedPercent > 0, "arrival soc");
        check(!result.personalized, "factory forecast");
    }

    private static void accountsForRoundTripAndFastMode() {
        RouteEnergyEstimator.Result oneWay = RouteEnergyEstimator.estimate(
                input(10, false, "ECO", 49.0, 5, 80, 0, 0, 0, 0)
        );
        RouteEnergyEstimator.Result roundFast = RouteEnergyEstimator.estimate(
                input(10, true, "FAST", 49.0, 5, 80, 0, 0, 0, 0)
        );

        check(roundFast.totalDistanceKm == 20.0, "round trip distance");
        check(roundFast.expectedRangeKm < oneWay.expectedRangeKm, "fast mode range");
        check(roundFast.arrivalSocExpectedPercent <= oneWay.arrivalSocExpectedPercent,
                "round trip arrival");
    }

    private static void prefersPersonalLearning() {
        RouteEnergyEstimator.Result result = RouteEnergyEstimator.estimate(
                input(6, false, "BALANCED", 50.0, 15, 80, 20, 3.4, 3.8, 6)
        );

        check(result.personalized, "personal forecast");
        check(result.confidencePercent >= 70, "personal confidence");
        check(result.expectedRangeKm > 0, "personal range");
    }

    private static void penalizesColdHeavyClimb() {
        RouteEnergyEstimator.Result normal = RouteEnergyEstimator.estimate(
                input(5, false, "BALANCED", 50.0, 20, 75, 0, 0, 0, 0)
        );
        RouteEnergyEstimator.Result difficult = RouteEnergyEstimator.estimate(
                input(5, false, "BALANCED", 50.0, -5, 115, 180, 0, 0, 0)
        );

        check(difficult.expectedRangeKm < normal.expectedRangeKm, "condition penalty");
        check(difficult.safeRangeKm < normal.safeRangeKm, "safe condition penalty");
    }

    private static void rejectsForecastWithoutTelemetry() {
        RouteEnergyEstimator.Result result = RouteEnergyEstimator.estimate(
                input(5, false, "BALANCED", 0, 20, 75, 0, 0, 0, 0)
        );
        check(result.status == RouteEnergyEstimator.Status.NO_DATA, "no telemetry");
    }

    private static void handlesReserveWithoutLosingTelemetry() {
        for (double voltage : new double[] {44.0, 43.5}) {
            RouteEnergyEstimator.Result r = RouteEnergyEstimator.estimate(input(5, false, "ECO", voltage, 20, 75, 0, 0, 0, 0));
            check(r.hasBatteryData() && r.expectedRangeKm == 0, "reserve is a known zero range");
            check(r.status == RouteEnergyEstimator.Status.INSUFFICIENT, "reserve is insufficient, not no data");
            check(r.shortfallKm() == 5 && r.arrivalRangeKm() == 0, "reserve shortfall");
        }
    }

    private static void rejectsNonFiniteInputs() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            check(!RouteEnergyEstimator.estimate(input(5, false, "ECO", invalid, 20, 75, 0, 0, 0, 0)).hasBatteryData(), "invalid voltage");
            check(!RouteEnergyEstimator.estimate(input(invalid, false, "ECO", 50, 20, 75, 0, 0, 0, 0)).hasBatteryData(), "invalid distance");
            check(!RouteEnergyEstimator.estimate(input(5, false, "ECO", 50, 20, invalid, 0, 0, 0, 0)).hasBatteryData(), "invalid load");
        }
    }

    private static void keepsRangeWithoutDestination() {
        RouteEnergyEstimator.Result r = RouteEnergyEstimator.estimate(input(0, false, "ECO", 50, 20, 75, 0, 0, 0, 0));
        check(r.hasBatteryData() && r.status == RouteEnergyEstimator.Status.NO_ROUTE, "range available without a destination");
        check(r.expectedRangeKm > 0, "standalone range");
    }

    private static void reportsArrivalAndShortfallInKm() {
        RouteEnergyEstimator.Result r = RouteEnergyEstimator.estimate(input(5, false, "ECO", 50, 20, 75, 0, 0, 0, 0));
        check(Math.abs(r.arrivalRangeKm() - (r.expectedRangeKm - 5)) < 1e-9, "arrival subtracts route length");
        check(r.arrivalSafeRangeKm() <= r.arrivalRangeKm() && r.arrivalRangeKm() <= r.arrivalOptimisticRangeKm(), "ordered interval");
        RouteEnergyEstimator.Result far = RouteEnergyEstimator.estimate(input(100, false, "ECO", 50, 20, 75, 0, 0, 0, 0));
        check(far.arrivalRangeKm() == 0 && far.shortfallKm() > 0, "explicit deficit instead of negative range");
    }

    private static void requiresThreeTripsForPersonalization() {
        RouteEnergyEstimator.Result two = RouteEnergyEstimator.estimate(input(5, false, "ECO", 50, 20, 75, 0, 80, 80, 2));
        check(!two.personalized, "two trips cannot override factory prior");
        check(RouteEnergyEstimator.estimate(input(5, false, "ECO", 50, 20, 75, 0, 3, 3, 3)).personalized, "three trips can personalize");
    }

    private static void preservesClimbDensityOnReturn() {
        RouteEnergyEstimator.Result one = RouteEnergyEstimator.estimate(input(5, false, "ECO", 50, 20, 75, 100, 0, 0, 0));
        RouteEnergyEstimator.Result round = RouteEnergyEstimator.estimate(input(5, true, "ECO", 50, 20, 75, 100, 0, 0, 0));
        check(Math.abs(one.expectedRangeKm - round.expectedRangeKm) < 1e-9, "return does not dilute uphill penalty");
        check(round.totalDistanceKm == 10, "doubles distance");
    }

    private static RouteEnergyEstimator.Input input(
            double distance,
            boolean roundTrip,
            String profile,
            double currentVoltage,
            double temperature,
            double loadKg,
            double climbM,
            double learned,
            double profileLearned,
            int trips
    ) {
        return new RouteEnergyEstimator.Input(
                distance,
                roundTrip,
                profile,
                currentVoltage,
                54.6,
                44.0,
                temperature,
                loadKg,
                climbM,
                learned,
                profileLearned,
                trips
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
