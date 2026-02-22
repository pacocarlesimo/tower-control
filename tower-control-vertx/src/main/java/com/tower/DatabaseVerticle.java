package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

public class DatabaseVerticle extends AbstractVerticle {

    private PgPool pool;

    @Override
    public void start(Promise<Void> start) {

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost("localhost")
                .setPort(5432)
                .setDatabase("tower_control")
                .setUser("tower")
                .setPassword("tower");

        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);

        pool = PgPool.pool(vertx, connectOptions, poolOptions);

        pool.getConnection()
                .onSuccess(conn -> {
                    conn.close();
                    System.out.println("[DB] Connected to PostgreSQL");
                    registerConsumers();
                    start.complete();
                })
                .onFailure(start::fail);
    }

    private void registerConsumers() {

        vertx.eventBus().<JsonObject>consumer("drone.position", message -> {
            JsonObject p = message.body();
            pool.preparedQuery("""
                INSERT INTO drone_position (drone_id, position, speed_ms, altitude_m, dx, dy, dz)
                VALUES ($1, ST_SetSRID(ST_MakePoint($2, $3), 4326), $4, $5, $6, $7, $8)
            """).execute(Tuple.of(
                    p.getInteger("id"),
                    p.getDouble("lon"),
                    p.getDouble("lat"),
                    p.getDouble("speed_ms"),
                    p.getDouble("altitude_m"),
                    p.getDouble("dx"),
                    p.getDouble("dy"),
                    p.getDouble("dz")
            )).onFailure(err ->
                    System.err.println("[DB] Failed to insert position: " + err.getMessage())
            );
        });

        vertx.eventBus().<JsonObject>consumer("drone.incident", message -> {
            JsonObject incident = message.body();
            JsonArray droneIds = incident.getJsonArray("drone_ids");
            double lon = incident.getDouble("lon");
            double lat = incident.getDouble("lat");
            String actionTaken = incident.getString("action_taken");

            pool.preparedQuery("""
                INSERT INTO incident (location, action_taken)
                VALUES (ST_SetSRID(ST_MakePoint($1, $2), 4326), $3)
                RETURNING id
            """).execute(Tuple.of(lon, lat, actionTaken))
                    .onSuccess(rows -> {
                        long incidentId = rows.iterator().next().getLong("id");
                        for (int i = 0; i < droneIds.size(); i++) {
                            int droneId = droneIds.getInteger(i);
                            pool.preparedQuery("""
                                INSERT INTO incident_drone (incident_id, drone_id)
                                VALUES ($1, $2)
                            """).execute(Tuple.of(incidentId, droneId))
                                    .onFailure(err ->
                                            System.err.println("[DB] Failed to insert incident_drone: " + err.getMessage())
                                    );
                        }
                    })
                    .onFailure(err ->
                            System.err.println("[DB] Failed to insert incident: " + err.getMessage())
                    );
        });
    }
}