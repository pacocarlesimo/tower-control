package com.tower;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> start) {
        vertx.deployVerticle(new IngestionVerticle())
                .compose(id -> vertx.deployVerticle(new TcpServerVerticle()))
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