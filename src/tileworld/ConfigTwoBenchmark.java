package tileworld;

import tileworld.environment.TWEnvironment;

/**
 * Headless acceptance for Config Two: same 10 seeds, compares team reward mean / variance
 * for Phase2 vs Phase1 vs random baseline on the Config Two world (dense, short lifetime).
 */
public final class ConfigTwoBenchmark {

    private ConfigTwoBenchmark() {}

    public static void main(String[] args) {
        final int[] seeds = { 1001, 2002, 3003, 4004, 5005, 6006, 7007, 8008, 9009, 10010 };

        Parameters.quietSimulation = true;
        try {
            Parameters.applyConfigTwo();

            System.out.println("=== Config Two acceptance (10 fixed seeds, " + Parameters.endTime + " steps) ===");
            System.out.println("World: " + Parameters.xDimension + "x" + Parameters.yDimension
                    + "  lifeTime=" + Parameters.lifeTime
                    + "  initial fuel=" + Parameters.defaultFuelLevel);
            System.out.println();

            Result r2 = runSuite("Phase2Agent", true, false, seeds);

            Result r1 = runSuite("Phase1Agent (same Config Two world)", false, false, seeds);

            Result rr = runSuite("SimpleTWAgent (random walk)", false, true, seeds);

            System.out.println();
            System.out.println("=== Summary ===");
            System.out.printf("Mean reward: Phase2=%.2f  Phase1=%.2f  Random=%.2f%n",
                    r2.mean, r1.mean, rr.mean);
            System.out.printf("Std dev (pop): Phase2=%.2f  Phase1=%.2f  Random=%.2f%n",
                    r2.stdDev, r1.stdDev, rr.stdDev);
            System.out.printf("CV (std/mean): Phase2=%.3f  Phase1=%.3f  Random=%.3f%n",
                    cv(r2), cv(r1), cv(rr));

            System.out.println();
            if (r2.mean > r1.mean) {
                System.out.println("Phase2 mean reward is above Phase1 on this seed set.");
            } else {
                System.out.println("Phase2 mean is not above Phase1 on this seed set - consider tuning or phase 3-4.");
            }

            double cvPhase2 = cv(r2);
            if (cvPhase2 > 0.5 && r2.mean > 10) {
                System.out.println(
                        "Phase2 CV is high vs mean - often one seed with much higher reward; "
                                + "contention / expiry / map luck; coordination (phase 3-4) may help.");
            } else if (cvPhase2 <= 0.5 || r2.mean <= 10) {
                System.out.println("Phase2 spread (CV) is moderate or mean is low - review numbers above.");
            }
        } finally {
            Parameters.quietSimulation = false;
            Parameters.useRandomWalkBaseline = false;
            Parameters.usePhase2Agent = false;
            Parameters.applyConfigOne();
        }
    }

    private static double cv(Result r) {
        return r.mean > 1e-9 ? r.stdDev / r.mean : 0.0;
    }

    private static final class Result {
        double mean;
        double variancePop;
        double stdDev;
        int min;
        int max;
    }

    private static Result runSuite(String label, boolean phase2Agent, boolean randomBaseline, int[] seeds) {
        Parameters.usePhase2Agent = phase2Agent;
        Parameters.useRandomWalkBaseline = randomBaseline;

        System.out.println("--- " + label + " ---");
        int[] rewards = new int[seeds.length];
        for (int i = 0; i < seeds.length; i++) {
            TWEnvironment tw = new TWEnvironment(seeds[i]);
            tw.start();
            long steps = 0;
            while (steps < Parameters.endTime) {
                if (!tw.schedule.step(tw)) {
                    break;
                }
                steps = tw.schedule.getSteps();
            }
            rewards[i] = tw.getReward();
            System.out.printf("  seed=%d  team_reward=%d%n", seeds[i], rewards[i]);
            tw.finish();
        }

        Result r = summarize(rewards);
        System.out.printf("  MEAN=%.2f  VAR(pop)=%.2f  STD=%.2f  MIN=%d  MAX=%d  CV=%.3f%n",
                r.mean, r.variancePop, r.stdDev, r.min, r.max, cv(r));
        System.out.println();
        return r;
    }

    private static Result summarize(int[] rewards) {
        Result r = new Result();
        long sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int v : rewards) {
            sum += v;
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        r.min = min;
        r.max = max;
        r.mean = sum / (double) rewards.length;
        double sumSq = 0.0;
        for (int v : rewards) {
            double d = v - r.mean;
            sumSq += d * d;
        }
        r.variancePop = sumSq / rewards.length;
        r.stdDev = Math.sqrt(r.variancePop);
        return r;
    }
}
