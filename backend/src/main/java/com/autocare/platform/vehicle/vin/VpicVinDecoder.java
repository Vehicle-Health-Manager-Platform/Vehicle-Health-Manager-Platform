package com.autocare.platform.vehicle.vin;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Explicit internal opt-in only: not a Spring bean, route, startup task or cache. */
public final class VpicVinDecoder implements VinDecoder {
    static final URI ENDPOINT = URI.create("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/");
    private final boolean enabled;
    private final URI endpoint;
    private final Duration deadline;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public VpicVinDecoder() { this(false); }
    public VpicVinDecoder(boolean enabled) { this(enabled,ENDPOINT,Duration.ofSeconds(10)); }

    // Package-only loopback transport enables real local HTTP tests without a public URL override.
    VpicVinDecoder(boolean enabled, URI endpoint, Duration deadline) {
        if (endpoint == null || deadline == null || deadline.toMillis() < 1 || deadline.toMillis() > 10000
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null || endpoint.getUserInfo() != null
                || !ENDPOINT.getRawPath().equals(endpoint.getRawPath())
                || !(ENDPOINT.equals(endpoint) || "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getPort() > 0))
            throw new IllegalArgumentException("Invalid VIN provider configuration");
        this.enabled = enabled; this.endpoint = endpoint; this.deadline = deadline;
        this.client = enabled ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build() : null;
    }

    @Override public Result decode(String raw, Integer year, boolean consent) {
        String vin = VinDecoder.normalize(raw); VinDecoder.checkYear(year);
        if (!consent) throw new IllegalArgumentException("请明确同意本次VIN解码用途");
        if (!enabled) throw new Unavailable();
        if (Thread.currentThread().isInterrupted()) throw new Unavailable();
        try {
            var uri = URI.create(endpoint.toASCIIString()+vin+"?format=json"+(year == null ? "" : "&modelyear="+year));
            var request = HttpRequest.newBuilder(uri).timeout(deadline).header("Accept","application/json").header("User-Agent","Autocare-VinCandidate/1.0").GET().build();
            var pending = client.sendAsync(request,info -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            var types=response.headers().allValues("Content-Type");var encodings=response.headers().allValues("Content-Encoding");
            if (response.statusCode() != 200 || types.size() != 1 || !types.get(0).split(";",2)[0].strip().equalsIgnoreCase("application/json")
                    || encodings.size() > 1 || !encodings.isEmpty() && !"identity".equalsIgnoreCase(encodings.get(0).strip())) throw new Unavailable();
            return VpicVinResponse.parse(vin,year,json.readTree(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable(); }
        catch (Exception e) { throw new Unavailable(); }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (var buffer : chunks) {
                if (buffer.remaining() > 65536-output.size()) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("VIN response too large")); return; }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
