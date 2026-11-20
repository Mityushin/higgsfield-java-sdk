package io.github.mityushin.higgsfield;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public final class RequestHandle {
    private final HiggsfieldClient client;
    private final String requestId;
    private final URI statusUrl;
    private final URI cancelUrl;
    RequestHandle(HiggsfieldClient client, String requestId, URI statusUrl, URI cancelUrl) {
        this.client = client; this.requestId = requestId; this.statusUrl = statusUrl; this.cancelUrl = cancelUrl;
    }
    public String requestId() { return requestId; }
    public CompletableFuture<RequestStatus> statusAsync() { return client.statusAsync(requestId, statusUrl); }
    public RequestStatus status() { return HiggsfieldClient.await(statusAsync()); }
    public CompletableFuture<Void> cancelAsync() { return client.cancelAsync(cancelUrl); }
    public void cancel() { HiggsfieldClient.await(cancelAsync()); }
    public CompletableFuture<RequestStatus> waitForResultAsync(PollingOptions options) { return client.waitForResultAsync(this, options); }
    public RequestStatus waitForResult(PollingOptions options) { return HiggsfieldClient.await(waitForResultAsync(options)); }
}
