package tileworld.agent;

import sim.field.grid.ObjectGrid2D;
import tileworld.Parameters;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.environment.TWDirection;
import tileworld.environment.TWEnvironment;
import tileworld.environment.TWFuelStation;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.AstarPathGenerator;
import tileworld.planners.TWPath;
import tileworld.planners.TWPathStep;

/**
 * Phase-1 agent: memory-based target choice, A* on remembered obstacles, fuel
 * handling via cached fuel-station coordinates (sensed on the object grid).
 * Subclasses may override hooks for phase-2 (dynamic world) behaviour.
 */
public class Phase1Agent extends TWAgent {

    private final String name;

    private TWPath currentPath;
    private Integer planGoalX;
    private Integer planGoalY;
    private Integer lastFuelX;
    private Integer lastFuelY;
    protected int fuelSearchWaypointIndex;

    public Phase1Agent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(xpos, ypos, env, fuelLevel);
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void communicate() {
        Message message = new Message(getName(), "", "");
        getEnvironment().receiveMessage(message);
    }

    /** When true, discard the current path at the start of each think (frequent replanning). */
    protected boolean shouldInvalidatePlanEachStep() {
        return false;
    }

    /**
     * When fuel is at or below this value, prefer going to a known fuel station over
     * normal tile/hole pursuit (if station coordinates are cached).
     */
    protected double getLowFuelThreshold() {
        int span = getEnvironment().getxDimension() + getEnvironment().getyDimension();
        return Math.max(160.0, span * 1.6);
    }

    /**
     * If we have never seen the fuel station in-sensor, bias exploration toward map
     * center once fuel drops below this level (helps discover the station).
     */
    protected double getCriticalFuelUnknownStationThreshold() {
        return 130.0;
    }

    protected int getAstarMaxDepth() {
        return 6000;
    }

    /**
     * Start dedicated fuel-station search before fuel becomes critical.
     */
    protected double getFuelSearchStartThreshold() {
        return Parameters.defaultFuelLevel * 0.72;
    }

    @Override
    protected TWThought think() {
        if (shouldInvalidatePlanEachStep()) {
            voidPlan();
        }

        refreshFuelFromSensors();

        TWEnvironment env = getEnvironment();
        Object atFeet = env.getObjectGrid().get(getX(), getY());

        if (env.inFuelStation(this) && getFuelLevel() < Parameters.defaultFuelLevel - 1) {
            voidPlan();
            return new TWThought(TWAction.REFUEL, TWDirection.Z);
        }

        double fuel = getFuelLevel();

        if (fuel <= getLowFuelThreshold() && lastFuelX != null && lastFuelY != null) {
            TWThought move = planToCell(lastFuelX, lastFuelY);
            if (move != null) {
                return move;
            }
        }

        if (lastFuelX == null || lastFuelY == null) {
            if (fuel <= getFuelSearchStartThreshold()) {
                TWThought searchMove = moveAlongFuelSearchWaypoints();
                if (searchMove != null) {
                    return searchMove;
                }
            }
            if (fuel <= getCriticalFuelUnknownStationThreshold()) {
                voidPlan();
                return moveTowardMapCenter();
            }
        }

        if (atFeet instanceof TWHole && hasTile()) {
            voidPlan();
            return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
        }

        if (atFeet instanceof TWTile && carriedTiles.size() < 3) {
            voidPlan();
            return new TWThought(TWAction.PICKUP, TWDirection.Z);
        }

        if (hasTile()) {
            TWHole hole = findNearestValidHole();
            if (hole != null) {
                TWThought move = planToCell(hole.getX(), hole.getY());
                if (move != null) {
                    return move;
                }
            }
        }

        if (carriedTiles.size() < 3) {
            TWTile tile = findNearestValidTile();
            if (tile != null) {
                TWThought move = planToCell(tile.getX(), tile.getY());
                if (move != null) {
                    return move;
                }
            }
        }

        voidPlan();
        return randomCardinalMove();
    }

    /**
     * One step biased toward the map center (helps find the fuel station when its
     * location is not yet cached).
     */
    protected TWThought moveTowardMapCenter() {
        TWEnvironment env = getEnvironment();
        int mx = env.getxDimension() / 2;
        int my = env.getyDimension() / 2;
        int ax = getX();
        int ay = getY();
        if (ax < mx) {
            return new TWThought(TWAction.MOVE, TWDirection.E);
        }
        if (ax > mx) {
            return new TWThought(TWAction.MOVE, TWDirection.W);
        }
        if (ay < my) {
            return new TWThought(TWAction.MOVE, TWDirection.S);
        }
        if (ay > my) {
            return new TWThought(TWAction.MOVE, TWDirection.N);
        }
        return randomCardinalMove();
    }

    /**
     * Explore large map regions in a loop to increase fuel-station discovery chance:
     * center -> corners -> center -> ...
     */
    protected TWThought moveAlongFuelSearchWaypoints() {
        TWEnvironment env = getEnvironment();
        int maxX = env.getxDimension() - 1;
        int maxY = env.getyDimension() - 1;
        int cx = maxX / 2;
        int cy = maxY / 2;
        int[][] waypoints = new int[][] {
            {cx, cy},
            {1, 1},
            {maxX - 1, 1},
            {maxX - 1, maxY - 1},
            {1, maxY - 1}
        };

        int tries = waypoints.length;
        while (tries-- > 0) {
            int[] wp = waypoints[Math.floorMod(fuelSearchWaypointIndex, waypoints.length)];
            if (getX() == wp[0] && getY() == wp[1]) {
                fuelSearchWaypointIndex++;
                continue;
            }
            TWThought move = planToCell(wp[0], wp[1]);
            if (move != null) {
                return move;
            }
            fuelSearchWaypointIndex++;
        }
        return null;
    }

    protected boolean hasKnownFuelStation() {
        return lastFuelX != null && lastFuelY != null;
    }

    protected Integer getKnownFuelX() {
        return lastFuelX;
    }

    protected Integer getKnownFuelY() {
        return lastFuelY;
    }

    protected void setKnownFuelStation(int x, int y) {
        lastFuelX = x;
        lastFuelY = y;
    }

    @Override
    protected void act(TWThought thought) {
        switch (thought.getAction()) {
            case REFUEL:
                refuel();
                return;
            case PICKUP: {
                Object o = getEnvironment().getObjectGrid().get(getX(), getY());
                if (o instanceof TWTile) {
                    pickUpTile((TWTile) o);
                }
                return;
            }
            case PUTDOWN: {
                Object o = getEnvironment().getObjectGrid().get(getX(), getY());
                if (o instanceof TWHole) {
                    putTileInHole((TWHole) o);
                }
                return;
            }
            case MOVE:
            default:
                try {
                    move(thought.getDirection());
                } catch (CellBlockedException e) {
                    voidPlan();
                }
        }
    }

    private void refreshFuelFromSensors() {
        int r = Parameters.defaultSensorRange;
        int ax = getX();
        int ay = getY();
        TWEnvironment env = getEnvironment();
        ObjectGrid2D grid = env.getObjectGrid();
        int best = Integer.MAX_VALUE;
        Integer fx = null;
        Integer fy = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) > r) {
                    continue;
                }
                int nx = ax + dx;
                int ny = ay + dy;
                if (!env.isInBounds(nx, ny)) {
                    continue;
                }
                Object o = grid.get(nx, ny);
                if (o instanceof TWFuelStation) {
                    int d = Math.abs(dx) + Math.abs(dy);
                    if (d < best) {
                        best = d;
                        fx = nx;
                        fy = ny;
                    }
                }
            }
        }
        if (fx != null) {
            lastFuelX = fx;
            lastFuelY = fy;
        }
    }

    /**
     * Nearest tile that still exists on the world grid (full-memory scan).
     * Phase2 overrides to prefer sensor / recent memory first.
     */
    protected TWTile findNearestValidTile() {
        ObjectGrid2D mem = getMemory().getMemoryGrid();
        int w = getEnvironment().getxDimension();
        int h = getEnvironment().getyDimension();
        ObjectGrid2D world = getEnvironment().getObjectGrid();
        TWTile best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Object o = mem.get(x, y);
                if (!(o instanceof TWTile)) {
                    continue;
                }
                if (!(world.get(x, y) instanceof TWTile)) {
                    continue;
                }
                double d = getDistanceTo(x, y);
                if (d < bestD) {
                    bestD = d;
                    best = (TWTile) o;
                }
            }
        }
        return best;
    }

    protected TWHole findNearestValidHole() {
        ObjectGrid2D mem = getMemory().getMemoryGrid();
        int w = getEnvironment().getxDimension();
        int h = getEnvironment().getyDimension();
        ObjectGrid2D world = getEnvironment().getObjectGrid();
        TWHole best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Object o = mem.get(x, y);
                if (!(o instanceof TWHole)) {
                    continue;
                }
                if (!(world.get(x, y) instanceof TWHole)) {
                    continue;
                }
                double d = getDistanceTo(x, y);
                if (d < bestD) {
                    bestD = d;
                    best = (TWHole) o;
                }
            }
        }
        return best;
    }

    protected TWThought planToCell(int tx, int ty) {
        int sx = getX();
        int sy = getY();
        if (sx == tx && sy == ty) {
            return null;
        }

        if (planGoalX == null || planGoalX != tx || planGoalY == null || planGoalY != ty) {
            voidPlan();
            planGoalX = tx;
            planGoalY = ty;
        }

        if (currentPath == null || !currentPath.hasNext()) {
            AstarPathGenerator gen = new AstarPathGenerator(getEnvironment(), this, getAstarMaxDepth());
            TWPath path = gen.findPath(sx, sy, tx, ty);
            if (path == null || !path.hasNext()) {
                voidPlan();
                return null;
            }
            currentPath = path;
        }

        TWPathStep step = currentPath.popNext();
        return new TWThought(TWAction.MOVE, step.getDirection());
    }

    protected void voidPlan() {
        currentPath = null;
        planGoalX = null;
        planGoalY = null;
    }

    private TWThought randomCardinalMove() {
        TWDirection[] dirs = new TWDirection[] {
            TWDirection.N, TWDirection.S, TWDirection.E, TWDirection.W
        };
        TWDirection d = dirs[getEnvironment().random.nextInt(4)];
        return new TWThought(TWAction.MOVE, d);
    }
}
