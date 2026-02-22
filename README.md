# Tower Control — Real-Time Drone Airspace Management System

A full-stack system that ingests real-time drone telemetry over TCP, detects collision risks, reroutes drones, persists position history in PostGIS, and renders everything live in a 3D Angular frontend.

---

## Architecture

```
┌─────────────────┐     TCP (JSON)       ┌──────────────────────────────────────────────────┐
│  Python         │ ──────────────────►  │  Vert.x Backend                                  │
│  Drone Simulator│                      │                                                  │
│  (n threads)    │ ◄──────────────────  │  TcpServerVerticle ────► request/reply:          │
│                 │   REROUTE command    │    ↑ pause/resume        IngestionVerticle       │
└─────────────────┘                      │    └──── 429 ◄───────── (ArrayDeque)             │
                                         │                           │ drain 500/tick       │
                                         │                           ▼ publish              │
                                         │                         EventBus                 │
                                         │                           ├──► CollisionVerticle │
                                         │                           ├──► DatabaseVerticle  │
                                         │                           └──► WebSocketVerticle │
                                         └──────────────────────────────────────────────────┘
                                                                     │              │
                                                               PostgreSQL       WebSocket
                                                                                    ▼
                                                                        ┌─────────────────────┐
                                                                        │  Angular + Three.js │
                                                                        │  3D Live View       │
                                                                        └─────────────────────┘
                                                     
```

Hot path (fully non-blocking):
`Python → TCP → TcpServerVerticle → EventBus → CollisionVerticle + DatabaseVerticle + WebSocketVerticle`

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Real-time backend | Vert.x 4.5 |
| Database | PostgreSQL 16 + PostGIS |
| Drone simulator | Python 3 |
| Frontend | Angular 21 + Three.js |
| Infrastructure | Docker |

---

## Features

- Configurable number of drones streaming position updates over persistent TCP connections (tested with 50+ drones at 10Hz)
- Collision detection with 30-second trajectory projection and automatic REROUTE
- Waypoint-based flight plans across opposite edges of Rome's airspace
- All positions persisted to PostGIS as `GEOMETRY(POINT, 4326)`
- 3D airplane models oriented to their real flight vector
- GeoJSON export of full flight history per drone
- Backpressure system — bounded ingestion queue prevents event loop saturation under burst load

---

## Project Structure

```
tower-control/
├── docker-compose.yml
├── db/
│   ├── init.sql
│   └── seed_drones.sql
├── tower-control-vertx/
│   └── src/main/java/com/tower/
│       ├── Main.java
│       ├── MainVerticle.java
│       ├── TcpServerVerticle.java
│       ├── DroneFrameParser.java
│       ├── IngestionVerticle.java
│       ├── DatabaseVerticle.java
│       ├── CollisionVerticle.java
│       └── WebSocketVerticle.java
├── drone-simulator/
│   ├── main.py
│   └── requirements.txt
└── tower-control-angular/
    └── src/app/
        ├── app.ts
        ├── drone.service.ts
        └── scene3d.component.ts
```

---

## How to Run

### 1. Database

```bash
docker-compose up -d
psql -h localhost -U tower -d tower_control -f db/init.sql
psql -h localhost -U tower -d tower_control -f db/seed_drones.sql
```

### 2. Vert.x backend

```bash
cd tower-control-vertx
mvn compile exec:java -Dexec.mainClass="com.tower.Main"
```

### 3. Drone simulator

```bash
cd drone-simulator
pip install -r requirements.txt
python main.py
```

### 4. Angular frontend

```bash
cd tower-control-angular
npm install && ng serve
```

Open [http://localhost:4200](http://localhost:4200)

---

## TCP Protocol

Each drone holds a persistent TCP connection. Messages are newline-delimited JSON:

```json
{"id":3,"lat":41.9123,"lon":12.4871,"altitude_m":142.5,"speed_ms":15.0,"dx":0.71,"dy":-0.70,"dz":0.08,"timestamp":"2025-01-01T12:00:00Z"}
```

`DroneFrameParser` buffers incomplete frames and emits only complete ones. This is necessary because TCP is a stream — a single `recv()` can deliver half a frame or multiple frames concatenated. The parser accumulates bytes until it finds `\n`, then emits the complete frame.

The server writes back on the same socket when needed:

```json
{"action":"REROUTE","dx":0.3,"dy":0.9,"dz":0.1}
```

---

## Collision Detection

`CollisionVerticle` receives every drone position on the EventBus. Each time a position arrives it runs pairwise checks across all known drone states:

1. **Proximity pre-filter** — pairs further than 500m apart are skipped immediately (Haversine)
2. **Trajectory projection** — for close pairs, extrapolate position 30 seconds forward using current velocity vector
3. **Projected distance check** — if the projected positions are within the collision threshold, an incident is recorded and a REROUTE command is sent to both drones via their TCP sockets

With N=10 this is 45 pairs per incoming message. The pre-filter makes it viable at larger N without a spatial index.

---

## Flight Planning

Each drone thread builds a flight plan on startup: origin on one edge of Rome's bounding box, destination on the opposite edge, with 4 intermediate waypoints at random altitudes between 30–200m.

Direction at each tick is computed as a normalized 3D vector toward the current waypoint, converting lat/lon deltas to meters using the correct scale factor for the current latitude (`cos(lat)` for longitude degrees). This keeps speed in m/s correct regardless of position.

On REROUTE the drone discards its intermediate waypoints and generates new ones, keeping the original final destination.

---

## Coordinate System

Positions are stored as WGS84 (`SRID 4326`). The Three.js scene uses a local flat projection centered on Rome (`41.925°N, 12.475°E`):

```
scene_x = (lon - center_lon) * cos(center_lat) * 111000 / SCALE
scene_z = -(lat - center_lat) * 111000 / SCALE
scene_y = altitude_m / 200 * 3
```

`SCALE = 1376` maps Rome's ~20km width to 15 Three.js units.

---

## GeoJSON Export

The frontend accumulates the full position history per drone without truncation. The HUD has per-drone export buttons and an **ALL DRONES** button.

Each file is a `FeatureCollection` with a `LineString` for the path and sampled `Point` features with `altitude_m` as a property. Paste into [geojson.io](https://geojson.io) to see the 2D path over Rome, or drag into [kepler.gl](https://kepler.gl/demo) to see altitude in 3D.

---

## Database Schema

```sql
CREATE TABLE drone (
    id        SERIAL PRIMARY KEY,
    name      VARCHAR(50),
    is_active BOOLEAN DEFAULT true,
    is_broken BOOLEAN DEFAULT false
);

CREATE TABLE drone_position (
    id          SERIAL PRIMARY KEY,
    drone_id    INTEGER REFERENCES drone(id),
    position    GEOMETRY(POINT, 4326),
    speed_ms    DECIMAL(6,2),
    altitude_m  DECIMAL(8,2),
    dx          DECIMAL(6,4),
    dy          DECIMAL(6,4),
    dz          DECIMAL(6,4),
    recorded_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX ON drone_position USING GIST (position);
CREATE INDEX ON drone_position (drone_id, recorded_at DESC);

CREATE TABLE incident (
    id          SERIAL PRIMARY KEY,
    location    GEOMETRY(POINT, 4326),
    detected_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE incident_drone (
    incident_id INTEGER REFERENCES incident(id),
    drone_id    INTEGER REFERENCES drone(id),
    PRIMARY KEY (incident_id, drone_id)
);
```

---

## Key Technical Decisions

**Why Vert.x instead of Spring Boot?**
10 drones at 10Hz means 100 persistent TCP connections and 100 messages/second. Spring Boot's default thread-per-request model would allocate a thread per connection. Vert.x runs on 2–4 event loop threads and never blocks — the same model used in Node.js and Netty. For a system where latency matters more than ORM convenience, it's the right tool.

**Why the EventBus?**
`TcpServerVerticle` doesn't import `DatabaseVerticle` or `CollisionVerticle`. It publishes to `"drone.position"` and forgets. This means the collision engine and the database writer are completely decoupled — either can be replaced or scaled independently without touching the TCP layer.

**Why Haversine and not Euclidean distance on lat/lon?**
At 42°N, 1° of longitude = ~82km but 1° of latitude = ~111km. Raw coordinate subtraction gives wrong distances. Haversine (or the simplified flat-earth version with `cos(lat)` correction) gives correct metric distances.

**Why PostGIS instead of plain float columns?**
`GEOMETRY(POINT, 4326)` with a GIST index unlocks spatial queries: `ST_DWithin` for radius search, `ST_Within` for polygon containment (no-fly zones), `ST_Distance` with correct geodesic distance. Adding a no-fly zone becomes a single spatial query rather than custom geometry code.


![ScreenRecording2026-02-22at18 24 49-ezgif com-video-to-gif-converter](https://github.com/user-attachments/assets/028258f6-a45c-42a9-b394-e5da7c950fac)


