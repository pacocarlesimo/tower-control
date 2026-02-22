package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.Message;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

public class IngestionVerticle extends AbstractVerticle {

    private static final int MAX_QUEUE = 20_000;
    private static final int DRAIN_BATCH = 500;

    private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
    private boolean draining = false;

    private final AtomicLong totalAccepted = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();
    private final AtomicLong totalPublished = new AtomicLong();

    @Override
    public void start() {

        vertx.eventBus().<JsonObject>consumer("drone.position.ingest", this::handleIngest);

        // Log queue health every 2 seconds
        vertx.setPeriodic(2000, id -> logStats());

        System.out.println("[Ingest] Verticle started");
    }

    private void handleIngest(Message<JsonObject> msg) {
        if (queue.size() >= MAX_QUEUE) {
            totalRejected.incrementAndGet();
            msg.fail(429, "Ingestion queue full");
            return;
        }

        queue.addLast(msg.body());
        totalAccepted.incrementAndGet();

        msg.reply("OK");

        if (!draining) {
            draining = true;
            vertx.runOnContext(v -> drain());
        }
    }

    private void drain() {
        int processed = 0;

        while (!queue.isEmpty() && processed < DRAIN_BATCH) {
            JsonObject pos = queue.removeFirst();
            vertx.eventBus().publish("drone.position", pos);
            totalPublished.incrementAndGet();
            processed++;
        }

        if (!queue.isEmpty()) {
            vertx.runOnContext(v -> drain());
        } else {
            draining = false;
        }
    }

    private void logStats() {
        System.out.printf(
                "[Ingest] queue=%d | draining=%s | accepted=%d | rejected=%d | published=%d%n",
                queue.size(),
                draining,
                totalAccepted.get(),
                totalRejected.get(),
                totalPublished.get()
        );
    }
}