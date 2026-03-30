package tileworld;

/**
 * One-shot acceptance runner:
 * 1) validates non-negotiable stage-0 constraints,
 * 2) runs Config One benchmark,
 * 3) runs Config Two benchmark.
 *
 * Run this class before/after each strategy change and compare outputs.
 */
public final class StrategyAcceptanceChecklist {

    private StrategyAcceptanceChecklist() {}

    public static void main(String[] args) {
        System.out.println("=== Stage-0 Constraint Check ===");
        int hardFail = 0;
        int sensorRange = Parameters.defaultSensorRange;
        int defaultFuel = Parameters.defaultFuelLevel;
        long endTime = Parameters.endTime;

        hardFail += check("defaultSensorRange == 3 (Chebyshev sensing radius)",
                sensorRange == 3);
        hardFail += check("defaultFuelLevel == 500",
                defaultFuel == 500);
        hardFail += check("endTime == 5000",
                endTime == 5000);
        hardFail += check("TWAction contains MOVE/PICKUP/PUTDOWN/REFUEL",
                hasCoreActions());

        // NOTE: these checks point to code-level guarantees you should keep unchanged.
        System.out.println("- info: fuel cost per movement should remain in TWAgent.moveDir (direction != Z => fuel--).");
        System.out.println("- info: sense/communicate/act ordering should remain TWEnvironment order 2 then 3.");
        System.out.println("- info: messages are cleared each step at environment.step start.");
        System.out.println("- info: object lifetime comes from Parameters.lifeTime via TWObjectCreator.");

        if (hardFail > 0) {
            System.out.println("Stage-0 checks FAILED: " + hardFail + " hard constraints broken.");
            System.exit(2);
        }
        System.out.println("Stage-0 checks PASSED.\n");

        System.out.println("=== Config One (Phase1 vs Random) ===");
        ConfigOneBenchmark.main(new String[0]);

        System.out.println("=== Config Two (Phase2 vs Phase1 vs Random) ===");
        ConfigTwoBenchmark.main(new String[0]);

        System.out.println("=== Acceptance run complete ===");
    }

    private static int check(String label, boolean ok) {
        if (ok) {
            System.out.println("[PASS] " + label);
            return 0;
        }
        System.out.println("[FAIL] " + label);
        return 1;
    }

    private static boolean hasCoreActions() {
        boolean move = false, pickup = false, putdown = false, refuel = false;
        for (tileworld.agent.TWAction a : tileworld.agent.TWAction.values()) {
            if (a == tileworld.agent.TWAction.MOVE) {
                move = true;
            } else if (a == tileworld.agent.TWAction.PICKUP) {
                pickup = true;
            } else if (a == tileworld.agent.TWAction.PUTDOWN) {
                putdown = true;
            } else if (a == tileworld.agent.TWAction.REFUEL) {
                refuel = true;
            }
        }
        return move && pickup && putdown && refuel;
    }
}
