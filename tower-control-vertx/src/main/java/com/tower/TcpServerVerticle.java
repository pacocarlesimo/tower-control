package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.net.NetSocket;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpServerVerticle extends AbstractVerticle {

    private final ConcurrentHashMap<Integer, NetSocket> droneSockets = new ConcurrentHashMap<>();

    @Override
    public void start(Promise<Void> start) {

        // Receive reroute commands from CollisionVerticle and forward to the correct TCP socket
        vertx.eventBus().<JsonObject>consumer("drone.reroute", message -> {
            JsonObject reroute = message.body();
            Integer droneId = reroute.getInteger("id");
            if (droneId == null) return;

            NetSocket socket = droneSockets.get(droneId);
            if (socket != null) {
                socket.write(reroute.encode() + "\n");
            }
        });

        vertx.createNetServer().connectHandler(socket -> {

                    System.out.println("[TCP] Drone connected: " + socket.remoteAddress());

                    // Track lifecycle (NetSocket has no isClosed())
                    AtomicBoolean closed = new AtomicBoolean(false);

                    socket.closeHandler(v -> {
                        closed.set(true);
                        droneSockets.entrySet().removeIf(entry -> entry.getValue().equals(socket));
                        System.out.println("[TCP] Drone disconnected: " + socket.remoteAddress());
                    });

                    socket.exceptionHandler(err -> {
                        // exception can happen before/without close handler in some cases
                        System.err.println("[TCP] Socket error: " + err.getMessage());
                    });

                    // Parse newline-delimited frames from the TCP stream
                    socket.handler(new DroneFrameParser(frame -> {
                        final JsonObject position;
                        try {
                            position = new JsonObject(frame);
                        } catch (Exception e) {
                            System.err.println("[TCP] Bad frame: " + frame);
                            return;
                        }

                        Integer droneId = position.getInteger("id");
                        if (droneId != null) {
                            // Remember which socket belongs to which drone so we can send reroutes back
                            droneSockets.put(droneId, socket);
                        }

                        // Backpressure-aware ingestion: request/reply (ACK or FAIL)
                        vertx.eventBus().request("drone.position.ingest", position)
                                .onSuccess(ok -> {
                                    // Accepted by ingestion queue. Nothing else to do here.
                                })
                                .onFailure(err -> {
                                    // Overloaded (or ingestion verticle unavailable):
                                    // pause reading from this socket to apply backpressure to the sender
                                    socket.pause();

                                    // Simple retry: resume after a short delay (no polling, just our closed flag)
                                    vertx.setTimer(50, t -> {
                                        if (!closed.get()) {
                                            socket.resume();
                                        }
                                    });
                                });
                    }));

                }).listen(9000)
                .onSuccess(server -> {
                    System.out.println("[TCP] Listening on port 9000");
                    start.complete();
                })
                .onFailure(start::fail);
    }
}