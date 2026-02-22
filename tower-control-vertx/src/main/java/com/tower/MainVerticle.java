package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> start) {
        vertx.deployVerticle(new TcpServerVerticle())
                .compose(id -> vertx.deployVerticle(new DatabaseVerticle()))
                .compose(id -> vertx.deployVerticle(new CollisionVerticle()))
                .compose(id -> vertx.deployVerticle(new WebSocketVerticle()))
                .onSuccess(id -> {
                    System.out.println("[Tower] All verticles deployed");
                    start.complete();
                })
                .onFailure(start::fail);
    }
}

/*
TcpServerVerticle

open a TCP server on port 9000
accept N drone connections, one socket per drone
read newline-delimited JSON from each socket
handle partial reads (TCP is a stream, a full JSON line might arrive in multiple chunks)
publish each parsed drone position to EventBus at address drone.position
keep a map of droneId → socket so it can send REROUTE commands back to the right drone later
listen on EventBus at address drone.reroute
    for reroute commands from CollisionVerticle and forward them to the right TCP socket
 */

/*
DatabaseVerticle

subscribe to EventBus address "drone.position"
for every position received → async insert into drone_position table
    using Vert.x async PgPool — never blocks the event loop

subscribe to EventBus address "drone.incident"
for every incident received → insert into incident table, then insert into incident_drone for each drone involved

PgPool is configured once on start and reused for all queries
if a write fails → log the error, never crash the verticle

PgPool is Vert.x's async PostgreSQL client — it never blocks the event loop.
Every query fires and the result comes back via a callback.
Max pool size is 10 connections which is plenty for now.
ST_MakePoint($1, $2) takes lon first, then lat — that's PostGIS convention, easy to get wrong.

The incident insert uses RETURNING id to get the generated incident ID back so it can insert the incident_drone
    rows immediately after — that's a two-step async chain.
 */

/*
CollisionVerticle

subscribe to EventBus address "drone.position"
maintain an in-memory map of droneId → latest drone state (position, vector, speed, altitude)

every 1000ms run collision detection:
    pre-filter: skip any pair of drones further than 500m apart (proximity zone)
    for each pair within proximity zone:
        project both drones 30 seconds forward at current speed and direction
        if projected positions come within 50m threshold → collision detected
            pick candidate drone (lower id)
            compute deflection vector (90 degree rotation in horizontal plane)
            project candidate on new vector for 30s → check against all other drones
            if new path is clear → reroute candidate
            if new path still hits something → reroute the other drone instead
            publish reroute command to EventBus "drone.reroute"
            publish incident to EventBus "drone.incident"

distance calculation uses Haversine formula — we're working in lat/lon not flat space
longitude degrees are scaled by cos(lat) to account for earth curvature
collision threshold → 50 meters
proximity pre-filter → 500 meters
*/

/*
WebSocketVerticle:

open an HTTP server on port 8080 with a WebSocket endpoint at /live
accept Angular connections — multiple clients can connect simultaneously
keep a set of all connected WebSocket connections

subscribe to EventBus address "drone.position"
    every time a position arrives → broadcast to all connected Angular clients

subscribe to EventBus address "drone.reroute"
    every time a reroute is issued → broadcast to all connected Angular clients

subscribe to EventBus address "drone.incident"
    every time an incident is detected → broadcast to all connected Angular clients

on client disconnect → remove from connected set
message format is plain JSON for all three event types
 */