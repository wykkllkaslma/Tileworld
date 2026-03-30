package tileworld.agent;

import tileworld.Parameters;
import tileworld.environment.TWEntity;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.environment.TWEnvironment;

/**
 * Phase-2 behaviour for Config Two (dense spawns, short object lifetime, large map):
 * invalidate plans every step, prefer targets seen in sensor range then recently in memory,
 * raise fuel threshold for long trips to the fuel station.
 */
public class Phase2Agent extends Phase1Agent {

    public Phase2Agent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected boolean shouldInvalidatePlanEachStep() {
        return true;
    }

    @Override
    protected double getLowFuelThreshold() {
        int span = Parameters.xDimension + Parameters.yDimension;
        return Math.max(240.0, span * 2.25);
    }

    @Override
    protected double getCriticalFuelUnknownStationThreshold() {
        int span = Parameters.xDimension + Parameters.yDimension;
        return Math.max(170.0, span * 1.0);
    }

    @Override
    protected double getFuelSearchStartThreshold() {
        int span = Parameters.xDimension + Parameters.yDimension;
        return Math.max(260.0, span * 1.35);
    }

    @Override
    protected int getAstarMaxDepth() {
        return Math.min(12000, Math.max(6000, Parameters.xDimension * Parameters.yDimension));
    }

    @Override
    protected TWTile findNearestValidTile() {
        TWEntity e = getMemory().getClosestObjectInSensorRange(TWTile.class);
        if (e instanceof TWTile) {
            TWTile t = (TWTile) e;
            if (getEnvironment().getObjectGrid().get(t.getX(), t.getY()) instanceof TWTile) {
                return t;
            }
        }

        double recency = Math.max(4.0, Parameters.lifeTime * 0.42);
        TWTile recent = getMemory().getNearbyTile(getX(), getY(), recency);
        if (recent != null
                && getEnvironment().getObjectGrid().get(recent.getX(), recent.getY()) instanceof TWTile) {
            return recent;
        }

        return super.findNearestValidTile();
    }

    @Override
    protected TWHole findNearestValidHole() {
        TWEntity e = getMemory().getClosestObjectInSensorRange(TWHole.class);
        if (e instanceof TWHole) {
            TWHole h = (TWHole) e;
            if (getEnvironment().getObjectGrid().get(h.getX(), h.getY()) instanceof TWHole) {
                return h;
            }
        }

        double recency = Math.max(4.0, Parameters.lifeTime * 0.42);
        TWHole recent = getMemory().getNearbyHole(getX(), getY(), recency);
        if (recent != null
                && getEnvironment().getObjectGrid().get(recent.getX(), recent.getY()) instanceof TWHole) {
            return recent;
        }

        return super.findNearestValidHole();
    }
}
