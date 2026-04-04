package tileworld.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sim.field.grid.ObjectGrid2D;
import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.environment.TWEnvironment;

/**
 * Six-agent coordinated policy with role-specific exploration zones, shared
 * sightings and Q-style target scoring. No random fallback is used.
 */
public class Phase4Agent extends Phase2Agent {

    private static final String MSG_PREFIX = "P4";
    private static final String TYPE_TILE = "TILE";
    private static final String TYPE_HOLE = "HOLE";

    private static final double FUEL_SAFETY = 70.0;
    private static final double DELIVER_CARRY_BONUS = 0.1;
    private static final double DELIVER_OFFSET = 1.0;
    private static final double PICKUP_HOLE_WEIGHT = 0.3;
    private static final double PICKUP_OFFSET = 2.0;
    private static final double UNKNOWN_HOLE_DIST = 8.0;
    private static final double CARRY_BONUS_MULT = 1.3;
    private static final int CARRY_BONUS_DIST = 5;
    private static final int SWEEP_DELIVERY_RANGE = 3;

    private final int agentId;
    private final Role role;
    private final Zone zone;

    private String lastIntentType;
    private Integer lastIntentX;
    private Integer lastIntentY;

    private int sweepTargetX;
    private int sweepTargetY;
    private int sweepDir = 1;
    private boolean sweepInitialized;

    private final Map<String, RemoteWinner> remoteWinnerByKey = new HashMap<String, RemoteWinner>();
    private final Map<String, SharedObservation> sharedTileByKey = new HashMap<String, SharedObservation>();
    private final Map<String, SharedObservation> sharedHoleByKey = new HashMap<String, SharedObservation>();

    public Phase4Agent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel, int teamSize) {
        super(name, xpos, ypos, env, fuelLevel);
        this.memory = new Phase4WorkingMemory(this, env.schedule, env.getxDimension(), env.getyDimension());
        this.agentId = parseAgentId(name);
        this.role = Role.forAgentId(this.agentId);
        this.zone = buildZone(this.role, env);
    }

    @Override
    public void communicate() {
        if (!Parameters.phase4EnableCommunication) {
            return;
        }

        refreshFuelFromSensorsLocal();

        StringBuilder sb = new StringBuilder();
        sb.append(MSG_PREFIX).append("|id=").append(agentId);
        sb.append("|role=").append(role.name());

        if (hasKnownFuelStation()) {
            sb.append("|fuel=").append(getKnownFuelX()).append(",").append(getKnownFuelY());
        }

        SensorObservation tileObs = getNearestSensorObservation(TYPE_TILE);
        if (tileObs != null) {
            sb.append("|seenTile=").append(tileObs.x).append(",").append(tileObs.y);
        }

        SensorObservation holeObs = getNearestSensorObservation(TYPE_HOLE);
        if (holeObs != null) {
            sb.append("|seenHole=").append(holeObs.x).append(",").append(holeObs.y);
        }

        if (lastIntentType != null && lastIntentX != null && lastIntentY != null) {
            int d = manhattan(getX(), getY(), lastIntentX.intValue(), lastIntentY.intValue());
            double t = getEnvironment().schedule.getTime();
            sb.append("|intent=").append(lastIntentType).append(":").append(lastIntentX).append(",")
                    .append(lastIntentY).append(",").append(d).append(",").append(t);
        }

        getEnvironment().receiveMessage(new Message(getName(), "", sb.toString()));
    }

    @Override
    protected TWThought think() {
        lastIntentType = null;
        lastIntentX = null;
        lastIntentY = null;

        processMessages();
        refreshFuelFromSensorsLocal();

        TWThought immediate = planImmediateAction();
        if (immediate != null) {
            return immediate;
        }

        TWThought fuel = planFuelAction();
        if (fuel != null) {
            return fuel;
        }

        if (shouldForceSweep()) {
            TWThought sweepDeliver = planSweepDelivery();
            if (sweepDeliver != null) {
                return sweepDeliver;
            }
            TWThought sweep = planSweep();
            if (sweep != null) {
                return sweep;
            }
        }

        List<Int2D> holes = buildKnownObjects(TYPE_HOLE);
        List<Int2D> tiles = buildKnownObjects(TYPE_TILE);

        Candidate deliver = hasTile() ? findBestDeliverCandidate(holes) : null;
        Candidate pickup = carriedTiles.size() < 3 ? findBestPickupCandidate(tiles, holes) : null;

        if (deliver != null && (pickup == null || deliver.score >= pickup.score || carriedTiles.size() >= 3)) {
            TWThought move = pursue(TYPE_HOLE, deliver.x, deliver.y);
            if (move != null) {
                return move;
            }
        }

        if (pickup != null) {
            TWThought move = pursue(TYPE_TILE, pickup.x, pickup.y);
            if (move != null) {
                return move;
            }
        }

        TWThought sweep = planSweep();
        if (sweep != null) {
            return sweep;
        }

        TWThought recovery = super.moveAlongFuelSearchWaypoints();
        if (recovery != null) {
            return recovery;
        }
        return new TWThought(TWAction.MOVE, tileworld.environment.TWDirection.Z);
    }

    private TWThought planImmediateAction() {
        TWEnvironment env = getEnvironment();
        if (env.inFuelStation(this) && getFuelLevel() < Parameters.defaultFuelLevel - 1) {
            voidPlan();
            return new TWThought(TWAction.REFUEL, tileworld.environment.TWDirection.Z);
        }

        Object atFeet = env.getObjectGrid().get(getX(), getY());
        if (atFeet instanceof TWHole && hasTile()) {
            voidPlan();
            return new TWThought(TWAction.PUTDOWN, tileworld.environment.TWDirection.Z);
        }
        if (atFeet instanceof TWTile && carriedTiles.size() < 3) {
            voidPlan();
            return new TWThought(TWAction.PICKUP, tileworld.environment.TWDirection.Z);
        }
        return null;
    }

    private TWThought planFuelAction() {
        if (!hasKnownFuelStation()) {
            if (role == Role.FUEL_SCOUT || getFuelLevel() < 200.0) {
                return planSweep();
            }
            return null;
        }

        int fuelDist = manhattan(getX(), getY(), getKnownFuelX().intValue(), getKnownFuelY().intValue());
        if (getFuelLevel() <= fuelDist + FUEL_SAFETY) {
            return planToCell(getKnownFuelX().intValue(), getKnownFuelY().intValue());
        }
        return null;
    }

    private boolean shouldForceSweep() {
        double time = getEnvironment().schedule.getTime();
        return !hasKnownFuelStation() && (time <= getInitSweepSteps() || getFuelLevel() < 200.0);
    }

    private TWThought planSweepDelivery() {
        if (!hasTile()) {
            return null;
        }
        Candidate best = null;
        for (Int2D hole : buildKnownObjects(TYPE_HOLE)) {
            int d = manhattan(getX(), getY(), hole.x, hole.y);
            if (d > SWEEP_DELIVERY_RANGE || shouldYieldToRemote(TYPE_HOLE, hole.x, hole.y)) {
                continue;
            }
            double score = 1.0 / (d + 1.0);
            if (best == null || score > best.score) {
                best = new Candidate(hole.x, hole.y, score);
            }
        }
        return best == null ? null : pursue(TYPE_HOLE, best.x, best.y);
    }

    private Candidate findBestDeliverCandidate(List<Int2D> holes) {
        Candidate best = null;
        for (Int2D hole : holes) {
            if (shouldYieldToRemote(TYPE_HOLE, hole.x, hole.y)) {
                continue;
            }
            int dist = manhattan(getX(), getY(), hole.x, hole.y);
            if (dist > getDistCap()) {
                continue;
            }
            double q = (1.0 + carriedTiles.size() * DELIVER_CARRY_BONUS)
                    / (dist + getZonePenalty(hole.x, hole.y) + DELIVER_OFFSET);
            if (dist <= CARRY_BONUS_DIST) {
                q *= CARRY_BONUS_MULT;
            }
            if (best == null || q > best.score) {
                best = new Candidate(hole.x, hole.y, q);
            }
        }
        return best;
    }

    private Candidate findBestPickupCandidate(List<Int2D> tiles, List<Int2D> holes) {
        Candidate best = null;
        for (Int2D tile : tiles) {
            if (shouldYieldToRemote(TYPE_TILE, tile.x, tile.y)) {
                continue;
            }
            int dist = manhattan(getX(), getY(), tile.x, tile.y);
            if (dist > getDistCap()) {
                continue;
            }
            double holeDist = estimateNearestHoleDistance(tile, holes);
            double q = 1.0 / (dist + PICKUP_HOLE_WEIGHT * holeDist + getZonePenalty(tile.x, tile.y) + PICKUP_OFFSET);
            if (best == null || q > best.score) {
                best = new Candidate(tile.x, tile.y, q);
            }
        }
        return best;
    }

    private double estimateNearestHoleDistance(Int2D tile, List<Int2D> holes) {
        double best = Double.POSITIVE_INFINITY;
        for (Int2D hole : holes) {
            double d = manhattan(tile.x, tile.y, hole.x, hole.y);
            if (d < best) {
                best = d;
            }
        }
        return best == Double.POSITIVE_INFINITY ? UNKNOWN_HOLE_DIST : best;
    }

    private List<Int2D> buildKnownObjects(String type) {
        Map<String, Int2D> dedup = new LinkedHashMap<String, Int2D>();
        ObjectGrid2D world = getEnvironment().getObjectGrid();
        Phase4WorkingMemory memory = getPhase4Memory();
        List<Int2D> memoryPositions = TYPE_TILE.equals(type)
                ? memory.getKnownTilePositions(getMemoryThreshold())
                : memory.getKnownHolePositions(getMemoryThreshold());

        for (Int2D pos : memoryPositions) {
            if (isStillObject(type, pos.x, pos.y, world)) {
                dedup.put(type + ":" + pos.x + "," + pos.y, pos);
            }
        }

        Map<String, SharedObservation> shared = TYPE_TILE.equals(type) ? sharedTileByKey : sharedHoleByKey;
        for (SharedObservation obs : shared.values()) {
            if (isStillObject(type, obs.x, obs.y, world)) {
                dedup.put(type + ":" + obs.x + "," + obs.y, new Int2D(obs.x, obs.y));
            }
        }

        return new ArrayList<Int2D>(dedup.values());
    }

    private Phase4WorkingMemory getPhase4Memory() {
        return (Phase4WorkingMemory) getMemory();
    }

    private boolean isStillObject(String type, int x, int y, ObjectGrid2D world) {
        Object o = world.get(x, y);
        return TYPE_TILE.equals(type) ? (o instanceof TWTile) : (o instanceof TWHole);
    }

    private TWThought pursue(String type, int x, int y) {
        TWThought move = planToCell(x, y);
        if (move == null) {
            return null;
        }
        rememberIntent(type, x, y);
        return move;
    }

    private TWThought planSweep() {
        if (role == Role.FUEL_SCOUT) {
            return planFuelScoutSweep();
        }
        ensureSweepTarget();
        if (manhattan(getX(), getY(), sweepTargetX, sweepTargetY) <= 1) {
            advanceSweepTarget();
        }
        TWThought move = planToCell(sweepTargetX, sweepTargetY);
        if (move != null) {
            return move;
        }
        advanceSweepTarget();
        return planToCell(sweepTargetX, sweepTargetY);
    }

    private TWThought planFuelScoutSweep() {
        int[][] waypoints = getFuelScoutWaypoints();
        int idx = ((int) Math.floor(getEnvironment().schedule.getTime() / 15.0)) % waypoints.length;
        for (int i = 0; i < waypoints.length; i++) {
            int[] wp = waypoints[(idx + i) % waypoints.length];
            if (getX() == wp[0] && getY() == wp[1]) {
                continue;
            }
            TWThought move = planToCell(wp[0], wp[1]);
            if (move != null) {
                return move;
            }
        }
        return null;
    }

    private void ensureSweepTarget() {
        if (sweepInitialized) {
            return;
        }
        sweepTargetX = zone.minX;
        sweepTargetY = zone.minY;
        sweepDir = 1;
        sweepInitialized = true;
    }

    private void advanceSweepTarget() {
        int stepY = Math.max(1, Parameters.defaultSensorRange * 2);
        sweepTargetX = sweepDir > 0 ? zone.maxX : zone.minX;
        sweepDir = -sweepDir;
        sweepTargetY += stepY;
        if (sweepTargetY > zone.maxY) {
            sweepTargetY = zone.minY;
        }
    }

    private int[][] getFuelScoutWaypoints() {
        int maxX = getEnvironment().getxDimension() - 1;
        int maxY = getEnvironment().getyDimension() - 1;
        int cx = maxX / 2;
        int cy = maxY / 2;
        return new int[][] {
            {cx, cy},
            {cx, 1},
            {maxX - 1, cy},
            {cx, maxY - 1},
            {1, cy}
        };
    }

    private double getMemoryThreshold() {
        return Parameters.lifeTime <= 40 ? Math.min(200.0, Parameters.lifeTime * 3.0)
                : Math.min(200.0, Parameters.lifeTime * 2.0);
    }

    private int getInitSweepSteps() {
        return (int) Math.min(250.0, Parameters.lifeTime * 7.0);
    }

    private int getDistCap() {
        int base = Parameters.lifeTime <= 40 ? Math.min(Parameters.lifeTime, Parameters.xDimension / 3 + 10)
                : Math.min(Parameters.lifeTime, Parameters.xDimension / 3 + 6);
        switch (role) {
            case FUEL_SCOUT:
                return Math.max(8, base - 8);
            case WEST_SCOUT:
            case EAST_SCOUT:
                return Math.max(10, base - 4);
            case CLOSER:
                return base + 6;
            default:
                return base;
        }
    }

    private double getZonePenalty(int x, int y) {
        if (role == Role.CLOSER) {
            return 0.0;
        }
        if (role == Role.FUEL_SCOUT && isInFuelCorridor(x, y)) {
            return 0.0;
        }
        return zone.contains(x, y) ? 0.0 : (Parameters.lifeTime <= 40 ? 5.0 : 3.0);
    }

    private boolean isInFuelCorridor(int x, int y) {
        int cx = getEnvironment().getxDimension() / 2;
        int cy = getEnvironment().getyDimension() / 2;
        int bandX = Math.max(3, getEnvironment().getxDimension() / 8);
        int bandY = Math.max(3, getEnvironment().getyDimension() / 8);
        return Math.abs(x - cx) <= bandX || Math.abs(y - cy) <= bandY;
    }

    private void refreshFuelFromSensorsLocal() {
        int r = Parameters.defaultSensorRange;
        ObjectGrid2D grid = getEnvironment().getObjectGrid();
        Integer fx = null;
        Integer fy = null;
        int best = Integer.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) > r) {
                    continue;
                }
                int nx = getX() + dx;
                int ny = getY() + dy;
                if (!getEnvironment().isInBounds(nx, ny)) {
                    continue;
                }
                Object o = grid.get(nx, ny);
                if (o instanceof TWFuelStation) {
                    int d = Math.abs(dx) + Math.abs(dy);
                    if (d < best) {
                        best = d;
                        fx = Integer.valueOf(nx);
                        fy = Integer.valueOf(ny);
                    }
                }
            }
        }

        if (fx != null && fy != null) {
            setKnownFuelStation(fx.intValue(), fy.intValue());
        }
    }

    private SensorObservation getNearestSensorObservation(String type) {
        int r = Parameters.defaultSensorRange;
        ObjectGrid2D grid = getEnvironment().getObjectGrid();
        SensorObservation best = null;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) > r) {
                    continue;
                }
                int nx = getX() + dx;
                int ny = getY() + dy;
                if (!getEnvironment().isInBounds(nx, ny)) {
                    continue;
                }
                Object o = grid.get(nx, ny);
                if (TYPE_TILE.equals(type) && !(o instanceof TWTile)) {
                    continue;
                }
                if (TYPE_HOLE.equals(type) && !(o instanceof TWHole)) {
                    continue;
                }
                int d = Math.abs(dx) + Math.abs(dy);
                if (best == null || d < best.distance) {
                    best = new SensorObservation(nx, ny, d);
                }
            }
        }

        return best;
    }

    private void processMessages() {
        if (!Parameters.phase4EnableCommunication) {
            remoteWinnerByKey.clear();
            sharedTileByKey.clear();
            sharedHoleByKey.clear();
            return;
        }

        double now = getEnvironment().schedule.getTime();
        Map<String, RemoteWinner> incomingClaims = new HashMap<String, RemoteWinner>();
        Map<String, SharedObservation> incomingTiles = new HashMap<String, SharedObservation>();
        Map<String, SharedObservation> incomingHoles = new HashMap<String, SharedObservation>();

        for (Message msg : getEnvironment().getMessages()) {
            if (msg == null || msg.getMessage() == null || getName().equals(msg.getFrom())) {
                continue;
            }
            String body = msg.getMessage();
            if (!body.startsWith(MSG_PREFIX + "|")) {
                continue;
            }
            int senderId = parseAgentId(msg.getFrom());
            for (String part : body.split("\\|")) {
                if (part.startsWith("fuel=")) {
                    int[] xy = parseXY(part.substring(5));
                    if (xy != null) {
                        setKnownFuelStation(xy[0], xy[1]);
                    }
                } else if (part.startsWith("intent=")) {
                    IntentParsed ip = parseIntentMessage(part.substring(7));
                    if (ip != null) {
                        String key = ip.type + ":" + ip.x + "," + ip.y;
                        RemoteWinner cand = new RemoteWinner(senderId, ip.dist, now);
                        RemoteWinner old = incomingClaims.get(key);
                        if (old == null || betterClaim(cand, old)) {
                            incomingClaims.put(key, cand);
                        }
                    }
                } else if (part.startsWith("seenTile=")) {
                    int[] xy = parseXY(part.substring(9));
                    if (xy != null) {
                        incomingTiles.put(TYPE_TILE + ":" + xy[0] + "," + xy[1], new SharedObservation(xy[0], xy[1], now));
                    }
                } else if (part.startsWith("seenHole=")) {
                    int[] xy = parseXY(part.substring(9));
                    if (xy != null) {
                        incomingHoles.put(TYPE_HOLE + ":" + xy[0] + "," + xy[1], new SharedObservation(xy[0], xy[1], now));
                    }
                }
            }
        }

        remoteWinnerByKey.putAll(incomingClaims);
        sharedTileByKey.putAll(incomingTiles);
        sharedHoleByKey.putAll(incomingHoles);

        pruneClaims(now);
        pruneObservations(sharedTileByKey, now);
        pruneObservations(sharedHoleByKey, now);
    }

    private void pruneClaims(double now) {
        Iterator<Map.Entry<String, RemoteWinner>> it = remoteWinnerByKey.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeenTime > getIntentTtl()) {
                it.remove();
            }
        }
    }

    private void pruneObservations(Map<String, SharedObservation> map, double now) {
        Iterator<Map.Entry<String, SharedObservation>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeenTime > getObservationTtl()) {
                it.remove();
            }
        }
    }

    private boolean shouldYieldToRemote(String type, int x, int y) {
        String key = type + ":" + x + "," + y;
        RemoteWinner rw = remoteWinnerByKey.get(key);
        if (rw == null) {
            return false;
        }
        int myDist = manhattan(getX(), getY(), x, y);
        if (rw.dist < myDist) {
            return true;
        }
        if (rw.dist > myDist) {
            return false;
        }
        return rw.senderId < agentId;
    }

    private void rememberIntent(String type, int x, int y) {
        lastIntentType = type;
        lastIntentX = Integer.valueOf(x);
        lastIntentY = Integer.valueOf(y);
    }

    private double getIntentTtl() {
        return Math.max(6.0, Parameters.lifeTime * 0.35);
    }

    private double getObservationTtl() {
        return Math.max(6.0, Parameters.lifeTime * 0.45);
    }

    private static boolean betterClaim(RemoteWinner a, RemoteWinner b) {
        if (a.dist != b.dist) {
            return a.dist < b.dist;
        }
        return a.senderId < b.senderId;
    }

    private static int manhattan(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) + Math.abs(ay - by);
    }

    private static Zone buildZone(Role role, TWEnvironment env) {
        int maxX = env.getxDimension() - 1;
        int maxY = env.getyDimension() - 1;
        int midX = env.getxDimension() / 2;
        int midY = env.getyDimension() / 2;
        int centerBand = Math.max(3, env.getxDimension() / 8);

        switch (role) {
            case FUEL_SCOUT:
                return new Zone(Math.max(1, midX - centerBand), Math.min(maxX - 1, midX + centerBand), 1, maxY - 1);
            case WEST_SCOUT:
                return new Zone(1, Math.max(1, midX - 1), 1, Math.max(1, midY - 1));
            case EAST_SCOUT:
                return new Zone(Math.min(maxX - 1, midX), maxX - 1, 1, Math.max(1, midY - 1));
            case WEST_COLLECTOR:
                return new Zone(1, Math.max(1, midX - 1), Math.min(maxY - 1, midY), maxY - 1);
            case EAST_COLLECTOR:
                return new Zone(Math.min(maxX - 1, midX), maxX - 1, Math.min(maxY - 1, midY), maxY - 1);
            case CLOSER:
            default:
                return new Zone(1, maxX - 1, 1, maxY - 1);
        }
    }

    private enum Role {
        FUEL_SCOUT,
        WEST_SCOUT,
        EAST_SCOUT,
        WEST_COLLECTOR,
        EAST_COLLECTOR,
        CLOSER;

        static Role forAgentId(int agentId) {
            switch (Math.max(1, agentId)) {
                case 1:
                    return FUEL_SCOUT;
                case 2:
                    return WEST_SCOUT;
                case 3:
                    return EAST_SCOUT;
                case 4:
                    return WEST_COLLECTOR;
                case 5:
                    return EAST_COLLECTOR;
                default:
                    return CLOSER;
            }
        }
    }

    private static final class Zone {
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;

        Zone(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private static final class Candidate {
        final int x;
        final int y;
        final double score;

        Candidate(int x, int y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private static final class SensorObservation {
        final int x;
        final int y;
        final int distance;

        SensorObservation(int x, int y, int distance) {
            this.x = x;
            this.y = y;
            this.distance = distance;
        }
    }

    private static final class SharedObservation {
        final int x;
        final int y;
        final double lastSeenTime;

        SharedObservation(int x, int y, double lastSeenTime) {
            this.x = x;
            this.y = y;
            this.lastSeenTime = lastSeenTime;
        }
    }

    private static final class RemoteWinner {
        final int senderId;
        final int dist;
        final double lastSeenTime;

        RemoteWinner(int senderId, int dist, double lastSeenTime) {
            this.senderId = senderId;
            this.dist = dist;
            this.lastSeenTime = lastSeenTime;
        }
    }

    private static final class IntentParsed {
        final String type;
        final int x;
        final int y;
        final int dist;

        IntentParsed(String type, int x, int y, int dist) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.dist = dist;
        }
    }

    private static IntentParsed parseIntentMessage(String payload) {
        int c = payload.indexOf(':');
        if (c <= 0 || c >= payload.length() - 1) {
            return null;
        }
        String[] nums = payload.substring(c + 1).split(",");
        if (nums.length != 4) {
            return null;
        }
        try {
            return new IntentParsed(payload.substring(0, c), Integer.parseInt(nums[0].trim()),
                    Integer.parseInt(nums[1].trim()), Integer.parseInt(nums[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int[] parseXY(String xy) {
        String[] p = xy.split(",");
        if (p.length != 2) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseAgentId(String name) {
        if (name == null) {
            return 999;
        }
        String digits = name.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return 999;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 999;
        }
    }
}
