package com.tower;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;

import java.util.function.Consumer;

public class DroneFrameParser implements Handler<Buffer> {

    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> onFrame;

    public DroneFrameParser(Consumer<String> onFrame) {
        this.onFrame = onFrame;
    }

    @Override
    public void handle(Buffer data) {
        buffer.append(data.toString());
        int newline;
        while ((newline = buffer.indexOf("\n")) != -1) {
            String frame = buffer.substring(0, newline).trim();
            buffer.delete(0, newline + 1);
            if (!frame.isEmpty()) {
                onFrame.accept(frame);
            }
        }
    }
}