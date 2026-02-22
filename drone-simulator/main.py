import threading
import socket
import json
import time
import random
import math
import psycopg2

DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "dbname": "tower_control",
    "user": "tower",
    "password": "tower"
}

VERTX_HOST = "localhost"
VERTX_PORT = 9000
TICK_MS    = 0.1
SIM_SPEED  = 20

LAT_MIN, LAT_MAX = 41.85, 42.00
LON_MIN, LON_MAX = 12.35, 12.60

WAYPOINT_THRESHOLD = 0.001
NUM_WAYPOINTS = 4

# Edge generators and their opposites
EDGES = {
    'north': lambda: (random.uniform(41.97, 42.00), random.uniform(LON_MIN, LON_MAX)),
    'south': lambda: (random.uniform(41.85, 41.88), random.uniform(LON_MIN, LON_MAX)),
    'east':  lambda: (random.uniform(LAT_MIN, LAT_MAX), random.uniform(12.55, 12.60)),
    'west':  lambda: (random.uniform(LAT_MIN, LAT_MAX), random.uniform(12.35, 12.40)),
}
OPPOSITE = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}


def load_active_drones():
    conn = psycopg2.connect(**DB_CONFIG)
    cur  = conn.cursor()
    cur.execute("""
                SELECT d.id, d.name,
                       ST_Y(dp.position) AS lat,
                       ST_X(dp.position) AS lon,
                       dp.altitude_m, dp.speed_ms
                FROM drone d
                         JOIN drone_position dp ON dp.drone_id = d.id
                WHERE d.is_active = true AND d.is_broken = false
                  AND dp.recorded_at = (
                    SELECT MAX(recorded_at) FROM drone_position WHERE drone_id = d.id
                )
                """)
    rows = cur.fetchall()
    cur.close()
    conn.close()
    return [
        {"id": r[0], "name": r[1], "lat": r[2], "lon": r[3],
         "altitude_m": r[4], "speed_ms": r[5]}
        for r in rows
    ]


def normalize(dx, dy, dz):
    mag = math.sqrt(dx**2 + dy**2 + dz**2)
    if mag == 0:
        return 0.0, 1.0, 0.0
    return dx / mag, dy / mag, dz / mag


def rand_altitude():
    return random.uniform(30.0, 200.0)


def build_flight_plan(start_lat, start_lon, fixed_dest=None):
    """
    Start on one edge of Rome, end on the opposite edge.
    Intermediate waypoints scattered randomly across the full airspace.
    """
    if fixed_dest:
        dest_lat, dest_lon = fixed_dest
    else:
        edge = random.choice(list(EDGES.keys()))
        opp  = OPPOSITE[edge]
        dest_lat, dest_lon = EDGES[opp]()

    # Intermediate waypoints: fully random across Rome, not interpolated
    waypoints = []
    for _ in range(NUM_WAYPOINTS):
        wlat = random.uniform(LAT_MIN, LAT_MAX)
        wlon = random.uniform(LON_MIN, LON_MAX)
        walt = rand_altitude()
        waypoints.append((wlat, wlon, walt))

    plan = [(start_lat, start_lon, 0.0)] + waypoints + [(dest_lat, dest_lon, 0.0)]
    return plan


def direction_to(from_lat, from_lon, from_alt, to_lat, to_lon, to_alt):
    METERS_PER_DEG_LAT = 111000
    METERS_PER_DEG_LON = 111000 * math.cos(math.radians(from_lat))
    dlat_m = (to_lat - from_lat) * METERS_PER_DEG_LAT
    dlon_m = (to_lon - from_lon) * METERS_PER_DEG_LON
    dalt_m = to_alt - from_alt
    return normalize(dlon_m, dlat_m, dalt_m)


def dist_to_waypoint(lat, lon, wlat, wlon):
    return math.sqrt((lat - wlat)**2 + (lon - wlon)**2)


def drone_thread(drone):
    lat      = drone["lat"]
    lon      = drone["lon"]
    altitude = drone["altitude_m"]
    speed    = drone["speed_ms"]
    drone_id = drone["id"]
    name     = drone["name"]

    flight_plan    = build_flight_plan(lat, lon)
    waypoint_index = 1

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.05)
    try:
        sock.connect((VERTX_HOST, VERTX_PORT))
        print(f"[{name}] Connected")
    except ConnectionRefusedError:
        print(f"[{name}] Vert.x not available")

    dest = flight_plan[-1]
    print(f"[{name}] Route: ({lat:.3f},{lon:.3f}) -> ({dest[0]:.3f},{dest[1]:.3f}) via {NUM_WAYPOINTS} waypoints")

    while True:
        target = flight_plan[waypoint_index]
        t_lat, t_lon, t_alt = target

        if dist_to_waypoint(lat, lon, t_lat, t_lon) < WAYPOINT_THRESHOLD and abs(altitude - t_alt) < 5.0:
            waypoint_index += 1
            print(f"[{name}] Waypoint {waypoint_index-1}/{len(flight_plan)-1} reached")

            if waypoint_index >= len(flight_plan):
                print(f"[{name}] Destination reached, new flight plan")
                flight_plan    = build_flight_plan(lat, lon)
                waypoint_index = 1
                dest = flight_plan[-1]
                print(f"[{name}] New route -> ({dest[0]:.3f},{dest[1]:.3f})")
                target = flight_plan[waypoint_index]
                t_lat, t_lon, t_alt = target

        dx, dy, dz = direction_to(lat, lon, altitude, t_lat, t_lon, t_alt)

        lat      += dy * speed * TICK_MS * SIM_SPEED * 0.00001
        lon      += dx * speed * TICK_MS * SIM_SPEED * 0.00001
        altitude += dz * speed * TICK_MS * SIM_SPEED
        altitude  = max(0.0, min(200.0, altitude))

        payload = json.dumps({
            "id":         drone_id,
            "lat":        round(lat, 7),
            "lon":        round(lon, 7),
            "altitude_m": round(altitude, 2),
            "speed_ms":   round(speed, 2),
            "dx":         round(dx, 4),
            "dy":         round(dy, 4),
            "dz":         round(dz, 4),
            "timestamp":  time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        }) + "\n"

        try:
            sock.sendall(payload.encode())
        except Exception:
            pass

        try:
            data = sock.recv(1024).decode().strip()
            if data:
                msg = json.loads(data)
                if msg.get("action") == "REROUTE":
                    print(f"[{name}] REROUTE — recalculating, keeping destination")
                    final_dest = (flight_plan[-1][0], flight_plan[-1][1])
                    flight_plan    = build_flight_plan(lat, lon, fixed_dest=final_dest)
                    waypoint_index = 1
        except (socket.timeout, json.JSONDecodeError, OSError):
            pass

        time.sleep(TICK_MS)


if __name__ == "__main__":
    drones = load_active_drones()
    print(f"Loaded {len(drones)} active drones")
    threads = []
    for drone in drones:
        t = threading.Thread(target=drone_thread, args=(drone,), daemon=True)
        t.start()
        threads.append(t)
        print(f"Started {drone['name']}")
    for t in threads:
        t.join()