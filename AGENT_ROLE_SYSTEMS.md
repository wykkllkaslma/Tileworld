# Six-Agent System Design

## Shared Team Goal

The shared team goal is to maximize total reward over 5000 steps by turning as many tile-hole matches into completed deliveries as possible, while avoiding duplicate pursuit, blind exploration overlap, and fuel starvation.

## Agent 1: Fuel Scout

### Individual goal

Find the fuel station as early as possible, broadcast its location to the full team, and keep the center corridor observable so every other agent can travel safely.

### Required behavior

- Move through the center cross of the map until the fuel station is confirmed.
- Broadcast fuel-station coordinates immediately after discovery.
- After fuel is known, stay active on center lanes and handle only center-lane tasks.
- Do not leave the center corridor for long-distance tile chasing.

## Agent 2: West Scout

### Individual goal

Continuously scan the west half of the map and broadcast fresh tile and hole sightings from that side.

### Required behavior

- Sweep the west half with a fixed patrol route.
- Prioritize coverage and information freshness over long pursuit.
- Accept only short-distance tasks inside the west service area.
- Keep publishing west-side tile and hole sightings for the collectors.

## Agent 3: East Scout

### Individual goal

Continuously scan the east half of the map and broadcast fresh tile and hole sightings from that side.

### Required behavior

- Sweep the east half with a fixed patrol route.
- Prioritize coverage and information freshness over long pursuit.
- Accept only short-distance tasks inside the east service area.
- Keep publishing east-side tile and hole sightings for the collectors.

## Agent 4: West Collector

### Individual goal

Convert west-side tile opportunities into reward by picking up tiles and delivering them to west-side holes.

### Required behavior

- Work only inside the west service area.
- Prioritize tile pickup when inventory is not full.
- Prioritize hole completion immediately when carrying tiles.
- Use west-side scout sightings and west-side claims to avoid duplicated pursuit.

## Agent 5: East Collector

### Individual goal

Convert east-side tile opportunities into reward by picking up tiles and delivering them to east-side holes.

### Required behavior

- Work only inside the east service area.
- Prioritize tile pickup when inventory is not full.
- Prioritize hole completion immediately when carrying tiles.
- Use east-side scout sightings and east-side claims to avoid duplicated pursuit.

## Agent 6: Closer

### Individual goal

Finish the highest-value open work across the whole map, with priority on hole completion and unresolved claims.

### Required behavior

- Prioritize hole targets whenever carrying at least one tile.
- When empty-handed, choose the best globally reachable tile with a nearby hole.
- Take work that is not already owned by a closer teammate claim.
- Stay mobile across the full map and absorb unserved opportunities.

## Communication Contract

Every agent must broadcast the following information each step:

- `role`: its role identity
- `intent`: the tile or hole it is currently pursuing
- `fuel`: known fuel-station coordinates when available
- `seenTile`: the strongest tile sighting from current sensing
- `seenHole`: the strongest hole sighting from current sensing

## Coordination Rule

For the same target cell, the winner is the agent with the lower Manhattan distance. If distances are equal, the lower agent id keeps the target.
