package tileworld;

import tileworld.environment.TWEnvironment;

/**
 * Phase4 evaluation:
 * - 6 agents
 * - compare no-communication vs communication
 * - run on Config One and Config Two with same 10 fixed seeds.
 */
public final class Phase4CommunicationBenchmark {

    private Phase4CommunicationBenchmark() {}

    private static final int[] SEEDS = { 1001, 2002, 3003, 4004, 5005, 6006, 7007, 8008, 9009, 10010 };

    public static void main(String[] args) {
        Parameters.quietSimulation = true;
        try {
            runForConfigOne();
            runForConfigTwo();
        } finally {
            resetFlags();
        }
    }

    private static void runForConfigOne() {
        Parameters.applyConfigOne();
        Parameters.usePhase4Agent = true;
        Parameters.phase4AgentCount = 6;

        System.out.println("=== Phase4 Communication A/B on Config One ===");
        System.out.println("World: " + Parameters.xDimension + "x" + Parameters.yDimension
                + " lifeTime=" + Parameters.lifeTime + " agents=" + Parameters.phase4AgentCount);
        Result noComm = runSuite("Phase4 no-communication", false);
        Result comm = runSuite("Phase4 communication", true);
        printDelta(noComm, comm);
        System.out.println();
    }

    private static void runForConfigTwo() {
        Parameters.applyConfigTwo();
        Parameters.usePhase4Agent = true;
        Parameters.phase4AgentCount = 6;

        System.out.println("=== Phase4 Communication A/B on Config Two ===");
        System.out.println("World: " + Parameters.xDimension + "x" + Parameters.yDimension
                + " lifeTime=" + Parameters.lifeTime + " agents=" + Parameters.phase4AgentCount);
        Result noComm = runSuite("Phase4 no-communication", false);
        Result comm = runSuite("Phase4 communication", true);
        printDelta(noComm, comm);
        System.out.println();
    }

    private static Result runSuite(String label, boolean communication) {
        Parameters.phase4EnableCommunication = communication;
        System.out.println("--- " + label + " ---");

        int[] rewards = new int[SEEDS.length];
        for (int i = 0; i < SEEDS.length; i++) {
            TWEnvironment tw = new TWEnvironment(SEEDS[i]);
            tw.start();
            long steps = 0;
            while (steps < Parameters.endTime) {
                if (!tw.schedule.step(tw)) {
                    break;
                }
                steps = tw.schedule.getSteps();
            }
            rewards[i] = tw.getReward();
            System.out.printf("  seed=%d  team_reward=%d%n", SEEDS[i], rewards[i]);
            tw.finish();
        }

        Result r = summarize(rewards);
        System.out.printf("  MEAN=%.2f  VAR(pop)=%.2f  STD=%.2f  MIN=%d  MAX=%d  CV=%.3f%n",
                r.mean, r.variancePop, r.stdDev, r.min, r.max, cv(r));
        return r;
    }

    private static void printDelta(Result noComm, Result comm) {
        double meanDelta = comm.mean - noComm.mean;
        double stdDelta = comm.stdDev - noComm.stdDev;
        System.out.println("=== Delta (communication - no-communication) ===");
        System.out.printf("  Mean delta=%.2f%n", meanDelta);
        System.out.printf("  Std delta=%.2f%n", stdDelta);
        if (meanDelta > 0) {
            System.out.println("  Communication improves mean team reward on this seed set.");
        } else if (meanDelta < 0) {
            System.out.println("  Communication does not improve mean on this seed set (needs retuning).");
        } else {
            System.out.println("  Communication and no-communication means are equal on this seed set.");
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

    private static void resetFlags() {
        Parameters.quietSimulation = false;
        Parameters.useRandomWalkBaseline = false;
        Parameters.usePhase2Agent = false;
        Parameters.usePhase4Agent = false;
        Parameters.phase4EnableCommunication = true;
        Parameters.phase4AgentCount = 6;
        Parameters.applyConfigOne();
    }
}
