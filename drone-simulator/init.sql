CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE operational_zone (
                                  id          SERIAL PRIMARY KEY,
                                  name        VARCHAR(100) NOT NULL,
                                  zone        GEOMETRY(POLYGON, 4326) NOT NULL,
                                  created_at  TIMESTAMPTZ DEFAULT now()
);

INSERT INTO operational_zone (name, zone) VALUES (
                                                     'Rome Airspace',
                                                     ST_GeomFromGeoJSON('{
        "type": "Polygon",
        "coordinates": [[
            [12.35, 41.85],
            [12.60, 41.85],
            [12.60, 42.00],
            [12.35, 42.00],
            [12.35, 41.85]
        ]]
    }')
                                                 );

CREATE TABLE drone (
                       id            SERIAL PRIMARY KEY,
                       name          VARCHAR(100) NOT NULL,
                       is_active     BOOLEAN DEFAULT true,
                       is_broken     BOOLEAN DEFAULT false,
                       registered_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE drone_position (
                                id          BIGSERIAL PRIMARY KEY,
                                drone_id    INT NOT NULL REFERENCES drone(id),
                                position    GEOMETRY(POINT, 4326) NOT NULL,
                                speed_ms    FLOAT NOT NULL,
                                altitude_m  FLOAT NOT NULL,
                                dx          FLOAT NOT NULL,
                                dy          FLOAT NOT NULL,
                                dz          FLOAT NOT NULL,
                                recorded_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_drone_position_drone_id  ON drone_position(drone_id);
CREATE INDEX idx_drone_position_recorded  ON drone_position(recorded_at DESC);
CREATE INDEX idx_drone_position_geo       ON drone_position USING GIST(position);


CREATE TABLE incident (
                          id           BIGSERIAL PRIMARY KEY,
                          location     GEOMETRY(POINT, 4326) NOT NULL,
                          detected_at  TIMESTAMPTZ DEFAULT now(),
                          resolved_at  TIMESTAMPTZ,
                          action_taken TEXT NOT NULL
);

CREATE TABLE incident_drone (
                                incident_id  BIGINT NOT NULL REFERENCES incident(id),
                                drone_id     INT    NOT NULL REFERENCES drone(id),
                                PRIMARY KEY  (incident_id, drone_id)
);

CREATE INDEX idx_incident_detected ON incident(detected_at DESC);
