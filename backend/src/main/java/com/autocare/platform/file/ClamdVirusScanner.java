package com.autocare.platform.file;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ClamdVirusScanner implements VirusScanner {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "upload-scan-deadline"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final InetSocketAddress address;
    private final int port, connectMillis, totalMillis;
    private final boolean requireFreshDefinitions;
    public ClamdVirusScanner(String host, int port, int connectMillis, int totalMillis) {
        this(host, port, connectMillis, totalMillis, true);
    }
    public ClamdVirusScanner(String host, int port, int connectMillis, int totalMillis, boolean requireFreshDefinitions) {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || connectMillis < 1
            || totalMillis < connectMillis || totalMillis > 60000) throw new IllegalArgumentException("Invalid upload scanner configuration");
        address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) throw new IllegalArgumentException("Invalid upload scanner host configuration");
        this.port = port; this.connectMillis = connectMillis; this.totalMillis = totalMillis;
        this.requireFreshDefinitions = requireFreshDefinitions;
    }
    @Override public Result scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > FileValidator.MAX_BYTES) return Result.UNAVAILABLE;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMillis);
        if (requireFreshDefinitions && !freshDefinitions(end)) return Result.UNAVAILABLE;
        try (var socket = new Socket()) {
            int remaining = remaining(end);
            var deadline = DEADLINES.schedule(() -> { try { socket.close(); } catch (Exception ignored) {} }, remaining, TimeUnit.MILLISECONDS);
            try {
                socket.connect(address, Math.min(connectMillis, remaining));
                socket.setSoTimeout(remaining);
                var out = new DataOutputStream(socket.getOutputStream());
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    int length = Math.min(8192, bytes.length - offset);
                    out.writeInt(length); out.write(bytes, offset, length);
                }
                out.writeInt(0); out.flush();
                String result = reply(socket);
                if (System.nanoTime() >= end) return Result.UNAVAILABLE;
                if ("stream: OK".equals(result)) return Result.CLEAN;
                if (result != null && result.matches("stream: [A-Za-z0-9_.:-]{1,256} FOUND")) return Result.INFECTED;
                return Result.UNAVAILABLE;
            } finally { deadline.cancel(false); }
        } catch (Exception exception) { return Result.UNAVAILABLE; }
    }
    private String reply(Socket socket) throws Exception {
        var reply = new ByteArrayOutputStream(); var input = socket.getInputStream();
        while (reply.size() < 4096) {
            int value = input.read(); if (value < 0) return null;
            if (value == 0) return reply.toString(StandardCharsets.US_ASCII);
            reply.write(value);
        }
        return null;
    }
    private int remaining(long end) {
        long millis = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
        if (millis <= 0) throw new IllegalStateException("Scanner deadline reached");
        return (int) millis;
    }
    private boolean freshDefinitions(long end) {
        try (var socket = new Socket()) {
            int remaining = remaining(end);
            var deadline = DEADLINES.schedule(() -> { try { socket.close(); } catch (Exception ignored) {} }, remaining, TimeUnit.MILLISECONDS);
            try {
                socket.connect(address, Math.min(connectMillis, remaining)); socket.setSoTimeout(remaining);
                socket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
                String version = reply(socket); if (version == null) return false;
                String[] parts = version.split("/", 3);
                if (parts.length != 3 || !parts[0].matches("ClamAV [0-9A-Za-z.+-]+") || !parts[1].matches("[1-9][0-9]*")) return false;
                var updated = LocalDateTime.parse(parts[2].trim().replaceAll("\\s+", " "),
                    DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH)).toInstant(ZoneOffset.UTC);
                Instant now = Instant.now();
                return !updated.isBefore(now.minusSeconds(48 * 3600)) && !updated.isAfter(now.plusSeconds(300));
            } finally { deadline.cancel(false); }
        } catch (Exception exception) { return false; }
    }
}
