package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketVerticle extends AbstractVerticle {

    private final Set<ServerWebSocket> clients = ConcurrentHashMap.newKeySet();

    @Override
    public void start(Promise<Void> start) {

        vertx.createHttpServer()
                .webSocketHandler(ws -> {
                    if (!ws.path().equals("/live")) {
                        ws.reject();
                        return;
                    }

                    clients.add(ws);
                    System.out.println("[WS] Client connected: " + ws.remoteAddress());

                    ws.closeHandler(v -> {
                        clients.remove(ws);
                        System.out.println("[WS] Client disconnected: " + ws.remoteAddress());
                    });

                    ws.exceptionHandler(err -> {
                        clients.remove(ws);
                        System.err.println("[WS] Error: " + err.getMessage());
                    });
                })
                .listen(8080)
                .onSuccess(server -> {
                    System.out.println("[WS] Listening on port 8080");
                    registerConsumers();
                    start.complete();
                })
                .onFailure(start::fail);
    }

    private void registerConsumers() {

        vertx.eventBus().<JsonObject>consumer("drone.position", message ->
                broadcast(new JsonObject()
                        .put("type", "position")
                        .put("data", message.body()))
        );

        vertx.eventBus().<JsonObject>consumer("drone.reroute", message ->
                broadcast(new JsonObject()
                        .put("type", "reroute")
                        .put("data", message.body()))
        );

        vertx.eventBus().<JsonObject>consumer("drone.incident", message ->
                broadcast(new JsonObject()
                        .put("type", "incident")
                        .put("data", message.body()))
        );
    }

    private void broadcast(JsonObject message) {
        String encoded = message.encode();
        for (ServerWebSocket client : clients) {
            client.writeTextMessage(encoded);
        }
    }
}