package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class CollisionVerticle extends AbstractVerticle {

    private static final double COLLISION_THRESHOLD_M = 50.0;
    private static final double PROXIMITY_ZONE_M      = 500.0;
    private static final int    PROJECTION_SECONDS    = 30;
    private static final double METERS_PER_DEG_LAT   = 111320.0;

    private final ConcurrentHashMap<Integer, JsonObject> droneStates = new ConcurrentHashMap<>();

    @Override
    public void start(Promise<Void> start) {

        vertx.eventBus().<JsonObject>consumer("drone.position", message ->
                droneStates.put(message.body().getInteger("id"), message.body())
        );

        vertx.setPeriodic(1000, tick -> runCollisionDetection());

        System.out.println("[Collision] Verticle started");
        start.complete();
    }

    private void runCollisionDetection() {
        List<JsonObject> drones = new ArrayList<>(droneStates.values());

        for (int i = 0; i < drones.size(); i++) {
            for (int j = i + 1; j < drones.size(); j++) {
                JsonObject a = drones.get(i);
                JsonObject b = drones.get(j);

                double currentDist = haversine(
                        a.getDouble("lat"), a.getDouble("lon"),
                        b.getDouble("lat"), b.getDouble("lon")
                );

                if (currentDist > PROXIMITY_ZONE_M) continue;

                double[] projA = project(a, a.getDouble("dx"), a.getDouble("dy"));
                double[] projB = project(b, b.getDouble("dx"), b.getDouble("dy"));

                double projectedDist = haversine(projA[0], projA[1], projB[0], projB[1]);

                if (projectedDist < COLLISION_THRESHOLD_M) {
                    System.out.printf("[Collision] DETECTED between drone %d and drone %d — projected dist: %.1fm%n",
                            a.getInteger("id"), b.getInteger("id"), projectedDist);
                    handleCollision(a, b);
                }
            }
        }
    }

    private void handleCollision(JsonObject a, JsonObject b) {
        JsonObject candidate  = a.getInteger("id") < b.getInteger("id") ? a : b;
        JsonObject other      = a.getInteger("id") < b.getInteger("id") ? b : a;

        double[] deflectedVector = deflect(candidate.getDouble("dx"), candidate.getDouble("dy"), candidate.getDouble("dz"));

        if (isClearPath(candidate, deflectedVector)) {
            sendReroute(candidate, other, deflectedVector);
        } else {
            double[] otherDeflected = deflect(other.getDouble("dx"), other.getDouble("dy"), other.getDouble("dz"));
            sendReroute(other, candidate, otherDeflected);
        }
    }

    private boolean isClearPath(JsonObject drone, double[] newVector) {
        double[] projected = project(drone, newVector[0], newVector[1]);

        for (JsonObject other : droneStates.values()) {
            if (other.getInteger("id").equals(drone.getInteger("id"))) continue;

            double[] otherProjected = project(other, other.getDouble("dx"), other.getDouble("dy"));
            double dist = haversine(projected[0], projected[1], otherProjected[0], otherProjected[1]);

            if (dist < COLLISION_THRESHOLD_M) return false;
        }
        return true;
    }

    private void sendReroute(JsonObject rerouted, JsonObject other, double[] vector) {
        JsonObject reroute = new JsonObject()
                .put("id",     rerouted.getInteger("id"))
                .put("action", "REROUTE")
                .put("dx",     vector[0])
                .put("dy",     vector[1])
                .put("dz",     vector[2]);

        vertx.eventBus().publish("drone.reroute", reroute);

        double incidentLat = (rerouted.getDouble("lat") + other.getDouble("lat")) / 2;
        double incidentLon = (rerouted.getDouble("lon") + other.getDouble("lon")) / 2;

        JsonObject incident = new JsonObject()
                .put("drone_ids",    new JsonArray().add(rerouted.getInteger("id")).add(other.getInteger("id")))
                .put("lat",          incidentLat)
                .put("lon",          incidentLon)
                .put("action_taken", "REROUTED drone " + rerouted.getInteger("id"));

        vertx.eventBus().publish("drone.incident", incident);
    }

    private double[] project(JsonObject drone, double dx, double dy) {
        double lat   = drone.getDouble("lat");
        double lon   = drone.getDouble("lon");
        double speed = drone.getDouble("speed_ms");

        double metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));

        double newLat = lat + (dy * speed * PROJECTION_SECONDS) / METERS_PER_DEG_LAT;
        double newLon = lon + (dx * speed * PROJECTION_SECONDS) / metersPerDegLon;

        return new double[]{newLat, newLon};
    }

    private double[] deflect(double dx, double dy, double dz) {
        return normalize(-dy, dx, dz);
    }

    private double[] normalize(double dx, double dy, double dz) {
        double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (mag == 0) return new double[]{0, 1, 0};
        return new double[]{dx / mag, dy / mag, dz / mag};
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}