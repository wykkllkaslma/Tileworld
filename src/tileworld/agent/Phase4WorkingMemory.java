package tileworld.agent;

import java.util.ArrayList;
import java.util.List;
import sim.engine.Schedule;
import sim.field.grid.ObjectGrid2D;
import sim.util.Bag;
import sim.util.Int2D;
import sim.util.IntBag;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;

/**
 * Phase-4 memory extension that keeps lightweight timestamps for recently seen
 * tiles and holes without modifying the base memory implementation.
 */
public class Phase4WorkingMemory extends TWAgentWorkingMemory {

    private final double[][] lastSeenTime;
    private final TWAgent agent;

    public Phase4WorkingMemory(TWAgent agent, Schedule schedule, int x, int y) {
        super(agent, schedule, x, y);
        this.agent = agent;
        this.lastSeenTime = new double[x][y];
        for (int ix = 0; ix < x; ix++) {
            for (int iy = 0; iy < y; iy++) {
                lastSeenTime[ix][iy] = -1.0;
            }
        }
    }

    @Override
    public void updateMemory(Bag sensedObjects, IntBag objectXCoords, IntBag objectYCoords,
            Bag sensedAgents, IntBag agentXCoords, IntBag agentYCoords) {
        super.updateMemory(sensedObjects, objectXCoords, objectYCoords, sensedAgents, agentXCoords, agentYCoords);
        double now = getSimulationTime();
        for (int i = 0; i < sensedObjects.size(); i++) {
            Object o = sensedObjects.get(i);
            if (!(o instanceof TWTile) && !(o instanceof TWHole)) {
                continue;
            }
            int x = objectXCoords.get(i);
            int y = objectYCoords.get(i);
            if (x >= 0 && y >= 0 && x < lastSeenTime.length && y < lastSeenTime[0].length) {
                lastSeenTime[x][y] = now;
            }
        }
    }

    @Override
    public void removeAgentPercept(int x, int y) {
        super.removeAgentPercept(x, y);
        if (x >= 0 && y >= 0 && x < lastSeenTime.length && y < lastSeenTime[0].length) {
            lastSeenTime[x][y] = -1.0;
        }
    }

    public List<Int2D> getKnownTilePositions(double threshold) {
        return getKnownObjectPositions(threshold, TWTile.class);
    }

    public List<Int2D> getKnownHolePositions(double threshold) {
        return getKnownObjectPositions(threshold, TWHole.class);
    }

    private List<Int2D> getKnownObjectPositions(double threshold, Class<?> type) {
        List<Int2D> positions = new ArrayList<Int2D>();
        ObjectGrid2D grid = getMemoryGrid();
        double now = getSimulationTime();
        for (int x = 0; x < lastSeenTime.length; x++) {
            for (int y = 0; y < lastSeenTime[x].length; y++) {
                Object o = grid.get(x, y);
                if (o == null || !type.isInstance(o)) {
                    continue;
                }
                double seen = lastSeenTime[x][y];
                if (seen >= 0.0 && now - seen <= threshold) {
                    positions.add(new Int2D(x, y));
                }
            }
        }
        return positions;
    }

    private double getSimulationTime() {
        return agent.getEnvironment().schedule.getTime();
    }
}
