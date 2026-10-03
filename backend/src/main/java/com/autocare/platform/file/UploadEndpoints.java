package com.autocare.platform.file;

import java.net.URI;

public final class UploadEndpoints {
    private UploadEndpoints() {}
    public static String validate(String value, boolean allowInsecure, boolean publicEndpoint) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) throw new IllegalArgumentException();
            boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost());
            if ("http".equals(uri.getScheme()) && (!allowInsecure || (publicEndpoint && !loopback))) throw new IllegalArgumentException();
            return uri.toString();
        } catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid upload endpoint configuration"); }
    }
}
