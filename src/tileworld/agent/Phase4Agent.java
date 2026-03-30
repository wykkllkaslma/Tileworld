package tileworld.agent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import sim.field.grid.ObjectGrid2D;
import tileworld.Parameters;
import tileworld.environment.TWEntity;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.environment.TWEnvironment;

/**
 * Phase-4 agent: keeps Phase2 local strategy, adds lightweight communication and
 * deterministic conflict resolution for multi-agent coordination.
 */
public class Phase4Agent extends Phase2Agent {

    private static final String MSG_PREFIX = "P4";
    private static final String TYPE_TILE = "TILE";
    private static final String TYPE_HOLE = "HOLE";

    private final int agentId;
    private final int teamSize;
    private String lastIntentType;
    private Integer lastIntentX;
    private Integer lastIntentY;
    /**
     * Best remote claim per target cell (from others' messages), with last time we heard it.
     * Conflict rule: smaller Manhattan distance wins; tie-break by smaller agent id.
     */
    private final Map<String, RemoteWinner> remoteWinnerByKey = new HashMap<String, RemoteWinner>();
    private int sectorWaypointIndex;

    public Phase4Agent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel, int teamSize) {
        super(name, xpos, ypos, env, fuelLevel);
        this.agentId = parseAgentId(name);
        this.teamSize = Math.max(1, teamSize);
    }

    @Override
    public void communicate() {
        if (!Parameters.phase4EnableCommunication) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(MSG_PREFIX).append("|id=").append(agentId);
        if (hasKnownFuelStation()) {
            sb.append("|fuel=").append(getKnownFuelX()).append(",").append(getKnownFuelY());
        }
        if (lastIntentType != null && lastIntentX != null && lastIntentY != null) {
            int d = manhattan(getX(), getY(), lastIntentX, lastIntentY);
            double t = getEnvironment().schedule.getTime();
            sb.append("|intent=").append(lastIntentType).append(":").append(lastIntentX).append(",")
                    .append(lastIntentY).append(",").append(d).append(",").append(t);
        }
        sb.append("|role=").append(getSectorIndex()).append("/").append(getSectorCount());
        getEnvironment().receiveMessage(new Message(getName(), "", sb.toString()));
    }

    @Override
    protected TWThought think() {
        lastIntentType = null;
        lastIntentX = null;
        lastIntentY = null;
        processMessages();
        return super.think();
    }

    @Override
    protected TWTile findNearestValidTile() {
        TWEntity e = getMemory().getClosestObjectInSensorRange(TWTile.class);
        if (e instanceof TWTile) {
            TWTile t = (TWTile) e;
            if (isTileValid(t) && !shouldYieldToRemote(TYPE_TILE, t.getX(), t.getY())) {
                rememberIntent(TYPE_TILE, t.getX(), t.getY());
                return t;
            }
        }

        double recency = Math.max(4.0, Parameters.lifeTime * 0.42);
        TWTile recent = getMemory().getNearbyTile(getX(), getY(), recency);
        if (recent != null && isTileValid(recent) && !shouldYieldToRemote(TYPE_TILE, recent.getX(), recent.getY())) {
            rememberIntent(TYPE_TILE, recent.getX(), recent.getY());
            return recent;
        }

        ObjectGrid2D mem = getMemory().getMemoryGrid();
        ObjectGrid2D world = getEnvironment().getObjectGrid();
        int w = getEnvironment().getxDimension();
        int h = getEnvironment().getyDimension();
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
                if (shouldYieldToRemote(TYPE_TILE, x, y)) {
                    continue;
                }
                double d = getDistanceTo(x, y);
                if (d < bestD) {
                    bestD = d;
                    best = (TWTile) o;
                }
            }
        }
        if (best != null) {
            rememberIntent(TYPE_TILE, best.getX(), best.getY());
        }
        return best;
    }

    @Override
    protected TWHole findNearestValidHole() {
        TWEntity e = getMemory().getClosestObjectInSensorRange(TWHole.class);
        if (e instanceof TWHole) {
            TWHole h = (TWHole) e;
            if (isHoleValid(h) && !shouldYieldToRemote(TYPE_HOLE, h.getX(), h.getY())) {
                rememberIntent(TYPE_HOLE, h.getX(), h.getY());
                return h;
            }
        }

        double recency = Math.max(4.0, Parameters.lifeTime * 0.42);
        TWHole recent = getMemory().getNearbyHole(getX(), getY(), recency);
        if (recent != null && isHoleValid(recent) && !shouldYieldToRemote(TYPE_HOLE, recent.getX(), recent.getY())) {
            rememberIntent(TYPE_HOLE, recent.getX(), recent.getY());
            return recent;
        }

        ObjectGrid2D mem = getMemory().getMemoryGrid();
        ObjectGrid2D world = getEnvironment().getObjectGrid();
        int w = getEnvironment().getxDimension();
        int h = getEnvironment().getyDimension();
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
                if (shouldYieldToRemote(TYPE_HOLE, x, y)) {
                    continue;
                }
                double d = getDistanceTo(x, y);
                if (d < bestD) {
                    bestD = d;
                    best = (TWHole) o;
                }
            }
        }
        if (best != null) {
            rememberIntent(TYPE_HOLE, best.getX(), best.getY());
        }
        return best;
    }

    @Override
    protected TWThought moveAlongFuelSearchWaypoints() {
        int[][] waypoints = getSectorWaypoints();
        int tries = waypoints.length;
        while (tries-- > 0) {
            int[] wp = waypoints[Math.floorMod(sectorWaypointIndex, waypoints.length)];
            if (getX() == wp[0] && getY() == wp[1]) {
                sectorWaypointIndex++;
                continue;
            }
            TWThought move = planToCell(wp[0], wp[1]);
            if (move != null) {
                return move;
            }
            sectorWaypointIndex++;
        }
        return super.moveAlongFuelSearchWaypoints();
    }

    private boolean isTileValid(TWTile t) {
        return getEnvironment().getObjectGrid().get(t.getX(), t.getY()) instanceof TWTile;
    }

    private boolean isHoleValid(TWHole h) {
        return getEnvironment().getObjectGrid().get(h.getX(), h.getY()) instanceof TWHole;
    }

    private void rememberIntent(String type, int x, int y) {
        lastIntentType = type;
        lastIntentX = x;
        lastIntentY = y;
    }

    /**
     * Yield this target if another agent has a strictly better (distance, id) claim.
     */
    private boolean shouldYieldToRemote(String type, int x, int y) {
        if (!Parameters.phase4EnableCommunication) {
            return false;
        }
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

    private void processMessages() {
        if (!Parameters.phase4EnableCommunication) {
            remoteWinnerByKey.clear();
            return;
        }
        double now = getEnvironment().schedule.getTime();
        Map<String, RemoteWinner> incomingBest = new HashMap<String, RemoteWinner>();

        List<Message> messages = getEnvironment().getMessages();
        for (Message msg : messages) {
            if (msg == null || msg.getMessage() == null) {
                continue;
            }
            if (getName().equals(msg.getFrom())) {
                continue;
            }
            String body = msg.getMessage();
            if (!body.startsWith(MSG_PREFIX + "|")) {
                continue;
            }
            int senderId = parseAgentId(msg.getFrom());
            String[] parts = body.split("\\|");
            for (String part : parts) {
                if (part.startsWith("fuel=")) {
                    int[] xy = parseXY(part.substring(5));
                    if (xy != null) {
                        setKnownFuelStation(xy[0], xy[1]);
                    }
                } else if (part.startsWith("intent=")) {
                    IntentParsed ip = parseIntentMessage(part.substring(7));
                    if (ip == null) {
                        continue;
                    }
                    String key = ip.type + ":" + ip.x + "," + ip.y;
                    RemoteWinner cand = new RemoteWinner(senderId, ip.dist, now);
                    RemoteWinner old = incomingBest.get(key);
                    if (old == null || betterClaim(cand, old)) {
                        incomingBest.put(key, cand);
                    }
                }
            }
        }

        for (Map.Entry<String, RemoteWinner> e : incomingBest.entrySet()) {
            RemoteWinner rw = new RemoteWinner(e.getValue().senderId, e.getValue().dist, now);
            remoteWinnerByKey.put(e.getKey(), rw);
        }

        Iterator<Map.Entry<String, RemoteWinner>> it = remoteWinnerByKey.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RemoteWinner> e = it.next();
            if (incomingBest.containsKey(e.getKey())) {
                continue;
            }
            if (now - e.getValue().lastSeenTime > getIntentTtl()) {
                it.remove();
            }
        }
    }

    private static boolean betterClaim(RemoteWinner a, RemoteWinner b) {
        if (a.dist != b.dist) {
            return a.dist < b.dist;
        }
        return a.senderId < b.senderId;
    }

    /** Simulation-time horizon after which an unheard intent is dropped. */
    private double getIntentTtl() {
        return Math.max(6.0, Parameters.lifeTime * 0.35);
    }

    private static int manhattan(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) + Math.abs(ay - by);
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

    /** Format: TILE:x,y,dist,simTime */
    private static IntentParsed parseIntentMessage(String payload) {
        int c = payload.indexOf(':');
        if (c <= 0 || c >= payload.length() - 1) {
            return null;
        }
        String type = payload.substring(0, c);
        String rest = payload.substring(c + 1);
        String[] nums = rest.split(",");
        if (nums.length != 4) {
            return null;
        }
        try {
            int x = Integer.parseInt(nums[0].trim());
            int y = Integer.parseInt(nums[1].trim());
            int dist = Integer.parseInt(nums[2].trim());
            return new IntentParsed(type, x, y, dist);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int[][] getSectorWaypoints() {
        TWEnvironment env = getEnvironment();
        int w = env.getxDimension();
        int h = env.getyDimension();

        int cols = (int) Math.ceil(Math.sqrt(getSectorCount()));
        int rows = (int) Math.ceil(getSectorCount() / (double) cols);
        int sector = getSectorIndex();
        int row = sector / cols;
        int col = sector % cols;
        if (row >= rows) {
            row = rows - 1;
        }

        int x0 = (int) Math.floor(col * (w / (double) cols));
        int x1 = Math.max(x0, (int) Math.floor((col + 1) * (w / (double) cols)) - 1);
        int y0 = (int) Math.floor(row * (h / (double) rows));
        int y1 = Math.max(y0, (int) Math.floor((row + 1) * (h / (double) rows)) - 1);
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;

        return new int[][] {
            {cx, cy},
            {x0, y0},
            {x1, y0},
            {x1, y1},
            {x0, y1}
        };
    }

    private int getSectorIndex() {
        return Math.max(0, Math.min(getSectorCount() - 1, agentId - 1));
    }

    private int getSectorCount() {
        return Math.max(1, teamSize);
    }

    private static int[] parseXY(String xy) {
        String[] p = xy.split(",");
        if (p.length != 2) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]) };
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
