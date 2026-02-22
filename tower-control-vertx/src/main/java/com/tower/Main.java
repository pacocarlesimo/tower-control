package com.tower;
import io.vertx.core.Vertx;

public class Main {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle())
                .onSuccess(id -> System.out.println("[Tower] Started"))
                .onFailure(err -> {
                    System.err.println("[Tower] Failed to start: " + err.getMessage());
                    vertx.close();
                });
    }
}

/*
In Spring Boot you think in:
@Service, @Controller, @Repository
Everything is a bean, Spring wires it together
Threads are managed for you, you write blocking code naturally

In Vert.x you think in:
Verticles — the basic unit of work, like an actor
Event Bus — the nervous system, verticles talk to each other through it
Event Loop — single threaded per verticle, never block it
Everything is async with callbacks or futures

The golden rule of Vert.x:
Never block the event loop. No Thread.sleep(), no blocking DB calls, no heavy computation inline.
If you block, everything on that event loop freezes.

MainVerticle
    │
    ├── TcpServerVerticle        → listens for drone TCP connections on port 9000
    │                              parses incoming JSON, publishes to EventBus
    │
    ├── CollisionVerticle        → subscribes to EventBus
    │                              keeps in-memory drone state
    │                              runs collision detection every tick
    │                              sends REROUTE back via EventBus
    │
    ├── DatabaseVerticle         → subscribes to EventBus
    │                              async writes to PostgreSQL
    │
    └── WebSocketVerticle        → subscribes to EventBus
                                   broadcasts live state to Angular

How they talk:

TcpServerVerticle
    receives drone position
    → publishes to EventBus address "drone.position"

CollisionVerticle + DatabaseVerticle + WebSocketVerticle
    all subscribe to "drone.position"
    each does its own thing independently

The EventBus is like a pub/sub system internal to Vert.x.
Nobody knows about each other, they just publish and subscribe to named addresses.

Compared to Spring Boot:
Spring Boot                                 Vert.x
@Service                                    Verticle
ApplicationEventPublisher                   EventBus
@Async                                      everything is async by default
@Scheduled                                  vertx.setPeriodic()
Blocking DB call                            PgPool async client
*/