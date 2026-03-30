package tileworld;

/**
 * Starts the GUI with competition Config Two (80×80, dense spawns, lifetime 30)
 * and {@link Parameters#usePhase2Agent Phase2Agent} behaviour.
 * For Config One / Phase1, run {@link TWGUI#main} directly (defaults match Config One).
 */
public final class ConfigTwoLauncher {

    private ConfigTwoLauncher() {}

    public static void main(String[] args) {
        Parameters.applyConfigTwo();
        Parameters.usePhase2Agent = true;
        TWGUI.main(args);
    }
}
