package tileworld;

/**
 * Global simulation parameters. Fields used for environment size and object rates
 * are mutable so {@link #applyConfigOne()} / {@link #applyConfigTwo()} can switch
 * competition profiles before constructing {@link tileworld.environment.TWEnvironment}.
 */
public class Parameters {

    public final static int seed = 4162012;
    public static final long endTime = 5000;

    public static final int defaultFuelLevel = 500;
    public static final int defaultSensorRange = 3;

    public static int xDimension = 50;
    public static int yDimension = 50;

    public static double tileMean = 0.2;
    public static double holeMean = 0.2;
    public static double obstacleMean = 0.2;
    public static double tileDev = 0.05;
    public static double holeDev = 0.05;
    public static double obstacleDev = 0.05;
    public static int lifeTime = 100;

    /** When true, {@link tileworld.environment.TWEnvironment} spawns {@link tileworld.agent.Phase2Agent}. */
    public static boolean usePhase2Agent = false;

    /** When true, spawns {@link tileworld.agent.Phase4Agent} with multi-agent coordination. */
    public static boolean usePhase4Agent = false;

    /** Number of agents to create in phase4 mode. */
    public static int phase4AgentCount = 6;

    /** Toggle communication for A/B tests: false=phase4 no-comm, true=phase4 comm enabled. */
    public static boolean phase4EnableCommunication = true;

    /** When true, spawns {@link tileworld.agent.SimpleTWAgent} (random walk) for benchmarking. */
    public static boolean useRandomWalkBaseline = false;

    /** Suppresses routine agent stdout during headless benchmarks. */
    public static boolean quietSimulation = false;

    /** Competition Config One: 50×50, sparse objects, long lifetime. Uses {@link tileworld.agent.Phase1Agent}. */
    public static void applyConfigOne() {
        xDimension = 50;
        yDimension = 50;
        tileMean = 0.2;
        holeMean = 0.2;
        obstacleMean = 0.2;
        tileDev = 0.05;
        holeDev = 0.05;
        obstacleDev = 0.05;
        lifeTime = 100;
        usePhase2Agent = false;
        usePhase4Agent = false;
    }

    /**
     * Competition Config Two: 80×80, dense objects, short lifetime.
     * Does not change agent type; set {@link #usePhase2Agent} (or use {@link ConfigTwoLauncher}) as needed.
     */
    public static void applyConfigTwo() {
        xDimension = 80;
        yDimension = 80;
        tileMean = 2.0;
        holeMean = 2.0;
        obstacleMean = 2.0;
        tileDev = 0.5;
        holeDev = 0.5;
        obstacleDev = 0.5;
        lifeTime = 30;
    }
}
