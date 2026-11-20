package io.github.mityushin.higgsfield;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

class HiggsfieldClientTest {
    private static final String BASE = "https://api.example/";
    @Test void seedanceSubmissionAndPolling() {
        AtomicInteger polls = new AtomicInteger();
        var fake = new FakeHttp(request -> {
            assertEquals("Key id:secret", request.headers().firstValue("Authorization").orElseThrow());
            if (request.uri().getPath().contains("seedance")) {
                assertEquals("once", request.headers().firstValue("Idempotency-Key").orElseThrow());
                assertEquals("3600", request.headers().firstValue("Hf-Expires-After").orElseThrow());
                return "{\"request_id\":\"abc\",\"status_url\":\"" + BASE + "requests/abc/status\",\"cancel_url\":\"" + BASE + "requests/abc/cancel\"}";
            }
            return polls.incrementAndGet() == 1 ? "{\"status\":\"queued\"}" : "{\"status\":\"completed\",\"video\":{\"url\":\"https://cdn.example/a.mp4\"}}";
        });
        try (var client = client(fake)) {
            var model = client.models().get(io.github.mityushin.higgsfield.catalog.HiggsfieldModels.SEEDANCE_2_0_TEXT_TO_VIDEO);
            var handle = client.submit(model, Map.of("prompt", "sunset"), new SubmitOptions(null, "once", 3600L));
            var status = handle.waitForResult(new PollingOptions(Duration.ofMillis(10), Duration.ofSeconds(2), null));
            assertEquals("abc", handle.requestId());
            assertEquals("https://cdn.example/a.mp4", status.body().path("video").path("url").asText());
            assertEquals(2, polls.get());
        }
    }
    @Test void refusesCrossOriginStatusUrlWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        try (var client = client(new FakeHttp(request -> { calls.incrementAndGet(); return "{\"request_id\":\"x\",\"status_url\":\"https://evil.example/status\",\"cancel_url\":\"" + BASE + "cancel\"}"; }))) {
            assertThrows(IllegalArgumentException.class, () -> client.submit("model", Map.of()));
            assertEquals(1, calls.get());
        }
    }
    @Test void unknownStatusAndTimeoutDoNotCancelRemoteRequest() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        try (var client = client(new FakeHttp(request -> {
            if (request.uri().getPath().endsWith("cancel")) cancellations.incrementAndGet();
            return request.uri().getPath().contains("/x/") ? "{\"status\":\"future_status\"}" : "{\"status\":\"queued\"}";
        }))) {
            assertThrows(HiggsfieldException.class, () -> client.waitForResult("x", PollingOptions.defaults()));
            var future = client.waitForResultAsync("y", new PollingOptions(Duration.ofSeconds(1), Duration.ofMillis(50), null));
            var error = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("y"));
            assertEquals(0, cancellations.get());
        }
    }
    @Test void uploadDoesNotSendApiCredentialsToStorage() throws Exception {
        var file = java.nio.file.Files.createTempFile("higgsfield-upload", ".jpg");
        try {
            java.nio.file.Files.write(file, new byte[] {1, 2, 3});
            try (var client = client(new FakeHttp(request -> {
                if (request.uri().getHost().equals("api.example")) {
                    assertEquals("Key id:secret", request.headers().firstValue("Authorization").orElseThrow());
                    return "{\"upload_url\":\"https://storage.example/object\",\"public_url\":\"https://cdn.example/object\",\"upload_headers\":{\"Content-Type\":\"image/jpeg\"}}";
                }
                assertEquals("PUT", request.method());
                assertTrue(request.headers().firstValue("Authorization").isEmpty());
                assertEquals("image/jpeg", request.headers().firstValue("Content-Type").orElseThrow());
                return "";
            }))) {
                assertEquals("https://cdn.example/object", client.upload(file, "image/jpeg"));
            }
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }
    @Test void retriesRateLimitedStatusButNeverRetriesSubmit() {
        AtomicInteger calls = new AtomicInteger();
        FakeHttp fake = new FakeHttp(request -> {
            calls.incrementAndGet();
            return "{\"status\":\"completed\"}";
        }, () -> calls.get() == 1 ? 429 : 200);
        try (var client = client(fake)) {
            assertEquals("completed", client.status("abc").status());
            assertEquals(2, calls.get());
        }
        AtomicInteger submits = new AtomicInteger();
        FakeHttp rejected = new FakeHttp(request -> { submits.incrementAndGet(); return "unavailable"; }, () -> 503);
        try (var client = client(rejected)) {
            var error = assertThrows(HiggsfieldException.class, () -> client.submit("model", Map.of()));
            assertEquals(503, error.statusCode());
            assertEquals(1, submits.get());
        }
    }
    @Test void closingClientCompletesPendingLocalPollingWithoutRemoteCancellation() {
        AtomicInteger cancelRequests = new AtomicInteger();
        var client = client(new FakeHttp(request -> {
            if (request.uri().getPath().endsWith("cancel")) cancelRequests.incrementAndGet();
            return "{\"status\":\"queued\"}";
        }));
        var pending = client.waitForResultAsync("abc", new PollingOptions(Duration.ofMinutes(1), Duration.ofMinutes(2), null));
        client.close();
        assertTrue(pending.isCompletedExceptionally());
        assertEquals(0, cancelRequests.get());
    }
    @Test void agentSessionMessagesInterruptAndMedia() throws Exception {
        var calls = new java.util.ArrayList<String>();
        var file = java.nio.file.Files.createTempFile("agent-media", ".jpg");
        try (var client = client(new FakeHttp(request -> {
            calls.add(request.method() + " " + request.uri().getPath());
            if (request.uri().getHost().equals("storage.example")) {
                assertTrue(request.headers().firstValue("Authorization").isEmpty());
                return "";
            }
            assertEquals("Key id:secret", request.headers().firstValue("Authorization").orElseThrow());
            return switch (request.uri().getPath()) {
                case "/v1/agent/sessions" -> "{\"session_id\":\"sess-1\",\"status\":\"idle\"}";
                case "/v1/agent/sessions/sess-1/messages" -> request.method().equals("POST") ? "{\"message_id\":\"msg-1\"}" : "{\"status\":\"processing\",\"messages\":[]}";
                case "/v1/agent/media" -> "{\"id\":\"media-1\",\"url\":\"https://cdn.example/a.jpg\",\"upload_url\":\"https://storage.example/a.jpg\",\"content_type\":\"image/jpeg\"}";
                case "/v1/agent/media/media-1/confirm" -> "{\"status\":\"uploaded\"}";
                default -> "{}";
            };
        }))) {
            var agents = client.agents();
            assertEquals("sess-1", agents.createSession(Map.of()).path("session_id").asText());
            assertEquals("msg-1", agents.send("sess-1", "hello"));
            assertEquals("processing", agents.messages("sess-1", "msg-1").path("status").asText());
            agents.interrupt("sess-1");
            assertEquals("https://cdn.example/a.jpg", agents.upload(file, "jpg", "image"));
            assertTrue(calls.contains("POST /v1/agent/media/media-1/confirm"));
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }
    @Test void agentRunCompletesWithTerminalAssistantMessage() {
        try (var client = client(new FakeHttp(request -> request.method().equals("POST")
                ? "{\"message_id\":\"msg-1\"}"
                : "{\"status\":\"idle\",\"messages\":[{\"role\":\"assistant\",\"status\":\"completed\",\"message\":{\"text\":\"done\"}}]}"))) {
            var result = client.agents().run("sess-1", "hello", Duration.ofSeconds(5), null);
            assertEquals("completed", result.status());
            assertEquals("done", result.message().path("message").path("text").asText());
        }
    }
    @Test void subscribeReportsAcceptedIdBeforePollingAndDeleteUsesDeleteMethod() {
        var order = new java.util.ArrayList<String>();
        try (var client = client(new FakeHttp(request -> {
            if (request.method().equals("DELETE")) { order.add("delete"); return "{\"request_id\":\"abc\",\"status\":\"deleted\"}"; }
            if (request.method().equals("POST")) return "{\"request_id\":\"abc\",\"status_url\":\"https://api.example/requests/abc/status\",\"cancel_url\":\"https://api.example/requests/abc/cancel\"}";
            order.add("poll");
            return "{\"status\":\"completed\"}";
        }))) {
            var result = client.subscribe("model", Map.of(), SubmitOptions.defaults(), PollingOptions.defaults(), handle -> order.add(handle.requestId()));
            assertTrue(result.isCompleted());
            assertEquals("deleted", client.delete("abc").path("status").asText());
            assertEquals(List.of("abc", "poll", "delete"), order);
        }
    }
    @Test void auxiliaryGetEncodesQueryAndKeepsCredentialsOnApiOrigin() {
        try (var client = client(new FakeHttp(request -> {
            assertEquals("/marketing-studio/image/presets", request.uri().getPath());
            assertEquals("cursor=a%2Fb", request.uri().getRawQuery());
            assertEquals("Key id:secret", request.headers().firstValue("Authorization").orElseThrow());
            return "{\"items\":[]}";
        }))) {
            assertTrue(client.get("marketing-studio/image/presets", Map.of("cursor", "a/b")).path("items").isArray());
            assertThrows(IllegalArgumentException.class, () -> client.get("https://evil.example", Map.of()));
        }
    }
    private static HiggsfieldClient client(HttpClient http) { return HiggsfieldClient.builder().baseUrl(URI.create(BASE)).credentials("id:secret").httpClient(http).build(); }
    private static final class FakeHttp extends HttpClient {
        private final Function<HttpRequest, String> response;
        private final java.util.function.IntSupplier status;
        FakeHttp(Function<HttpRequest, String> response) { this(response, () -> 200); }
        FakeHttp(Function<HttpRequest, String> response, java.util.function.IntSupplier status) { this.response = response; this.status = status; }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            @SuppressWarnings("unchecked") T body = (T) response.apply(r);
            HttpResponse<T> result = new HttpResponse<>() {
                public int statusCode() { return status.getAsInt(); } public HttpRequest request() { return r; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of("Retry-After", List.of("0")), (a,b) -> true); }
                public T body() { return body; } public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return r.uri(); } public Version version() { return Version.HTTP_1_1; }
            };
            return CompletableFuture.completedFuture(result);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r,h); }
        @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); } public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; } public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { return null; } public SSLParameters sslParameters() { return null; }
        public Optional<Authenticator> authenticator() { return Optional.empty(); } public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
    }
}
