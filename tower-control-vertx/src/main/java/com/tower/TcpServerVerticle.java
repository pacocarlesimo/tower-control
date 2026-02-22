package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.net.NetSocket;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.ConcurrentHashMap;

public class TcpServerVerticle extends AbstractVerticle {

    private final ConcurrentHashMap<Integer, NetSocket> droneSockets = new ConcurrentHashMap<>();

    @Override
    public void start(Promise<Void> start) {

        vertx.eventBus().<JsonObject>consumer("drone.reroute", message -> {
            JsonObject reroute = message.body();
            int droneId = reroute.getInteger("id");
            NetSocket socket = droneSockets.get(droneId);
            if (socket != null) {
                socket.write(reroute.encode() + "\n");
            }
        });

        vertx.createNetServer().connectHandler(socket -> {
            System.out.println("[TCP] Drone connected: " + socket.remoteAddress());

            socket.handler(new DroneFrameParser(frame -> {
                try {
                    JsonObject position = new JsonObject(frame);
                    int droneId = position.getInteger("id");
                    droneSockets.put(droneId, socket);
                    vertx.eventBus().publish("drone.position", position);
                } catch (Exception e) {
                    System.err.println("[TCP] Bad frame: " + frame);
                }
            }));

            socket.closeHandler(v -> {
                droneSockets.entrySet().removeIf(entry -> entry.getValue().equals(socket));
                System.out.println("[TCP] Drone disconnected: " + socket.remoteAddress());
            });

            socket.exceptionHandler(err ->
                    System.err.println("[TCP] Socket error: " + err.getMessage())
            );

        }).listen(9000).onSuccess(server -> {
            System.out.println("[TCP] Listening on port 9000");
            start.complete();
        }).onFailure(start::fail);
    }
}