package tileworld;

/**
 * Starts GUI with phase4 multi-agent coordination.
 * Args:
 * - no args: Config Two + communication on
 * - "config1": use Config One world
 * - "nocomm": disable communication
 */
public final class ConfigPhase4Launcher {

    private ConfigPhase4Launcher() {}

    public static void main(String[] args) {
        boolean useConfigOne = false;
        boolean noComm = false;
        for (String arg : args) {
            if ("config1".equalsIgnoreCase(arg)) {
                useConfigOne = true;
            } else if ("nocomm".equalsIgnoreCase(arg)) {
                noComm = true;
            }
        }

        if (useConfigOne) {
            Parameters.applyConfigOne();
        } else {
            Parameters.applyConfigTwo();
        }
        Parameters.usePhase4Agent = true;
        Parameters.usePhase2Agent = false;
        Parameters.useRandomWalkBaseline = false;
        Parameters.phase4AgentCount = 6;
        Parameters.phase4EnableCommunication = !noComm;
        TWGUI.main(args);
    }
}
