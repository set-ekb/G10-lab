package com.g10blelab.app;

public final class RouteEnergyEstimatorTest {

    public static void main(String[] args) {
        forecastsSafeRouteFromOfficialData();
        accountsForRoundTripAndFastMode();
        penalizesColdHeavyClimb();
        prefersPersonalLearning();
        rejectsForecastWithoutTelemetry();
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
