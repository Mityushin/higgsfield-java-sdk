package io.github.mityushin.higgsfield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mityushin.higgsfield.catalog.ModelCatalog;
import io.github.mityushin.higgsfield.catalog.ModelDescriptor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * General-purpose Higgsfield client. Async methods start immediately and do not retry
 * mutations.
 */
public final class HiggsfieldClient implements AutoCloseable {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();

	private final URI baseUrl;

	private final String credentials;

	private final Duration requestTimeout;

	private final int statusMaxRetries;

	private final HttpClient http;

	private final java.util.Set<CompletableFuture<RequestStatus>> waits = ConcurrentHashMap.newKeySet();

	private final java.util.Set<CompletableFuture<RequestStatus>> statusCalls = ConcurrentHashMap.newKeySet();

	private volatile boolean closed;

	private HiggsfieldClient(Builder builder) {
		baseUrl = normalizeBase(builder.baseUrl);
		credentials = Objects.requireNonNull(builder.credentials, "credentials");
		if (credentials.isBlank() || credentials.indexOf(':') <= 0 || credentials.endsWith(":"))
			throw new IllegalArgumentException("Credentials must have the form key:secret");
		requestTimeout = Objects.requireNonNull(builder.requestTimeout);
		statusMaxRetries = builder.statusMaxRetries;
		if (statusMaxRetries < 0 || statusMaxRetries > 10)
			throw new IllegalArgumentException("statusMaxRetries must be between 0 and 10");
		if (requestTimeout.isNegative() || requestTimeout.isZero())
			throw new IllegalArgumentException("requestTimeout must be positive");
		http = builder.http != null ? builder.http
				: HttpClient.newBuilder()
					.connectTimeout(builder.connectTimeout)
					.followRedirects(HttpClient.Redirect.NEVER)
					.build();
	}

	private static ScheduledThreadPoolExecutor createScheduler() {
		var executor = new ScheduledThreadPoolExecutor(1, r -> {
			var t = new Thread(r, "higgsfield-polling");
			t.setDaemon(true);
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Returns the offline model catalog shipped with this SDK version. */
	public ModelCatalog models() {
		return ModelCatalog.bundled();
	}

	public static final class Builder {

		private URI baseUrl = URI.create("https://api.higgsfield.ai/");

		private String credentials;

		private Duration requestTimeout = Duration.ofSeconds(60);

		private Duration connectTimeout = Duration.ofSeconds(10);

		private int statusMaxRetries = 2;

		private HttpClient http;

		Builder httpClient(HttpClient value) {
			http = value;
			return this;
		}

		public Builder baseUrl(URI value) {
			baseUrl = value;
			return this;
		}

		public Builder credentials(String value) {
			credentials = value;
			return this;
		}

		public Builder requestTimeout(Duration value) {
			requestTimeout = value;
			return this;
		}

		public Builder connectTimeout(Duration value) {
			connectTimeout = value;
			return this;
		}

		/**
		 * Maximum number of retries for a status GET; mutating calls are never retried.
		 */
		public Builder statusMaxRetries(int value) {
			statusMaxRetries = value;
			return this;
		}

		public HiggsfieldClient build() {
			return new HiggsfieldClient(this);
		}

	}

	public static HiggsfieldClient fromEnvironment() {
		String credentials = System.getenv("HF_CREDENTIALS");
		if (credentials == null || credentials.isBlank())
			credentials = System.getenv("HF_KEY");
		if (credentials == null || credentials.isBlank()) {
			String key = System.getenv("HF_API_KEY"), secret = System.getenv("HF_API_SECRET");
			if (key != null && secret != null)
				credentials = key + ":" + secret;
		}
		return builder().credentials(credentials).build();
	}

	public CompletableFuture<RequestHandle> submitAsync(String endpoint, Object input, SubmitOptions options) {
		Objects.requireNonNull(options);
		if (endpoint == null || !endpoint.matches("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*"))
			throw new IllegalArgumentException("Invalid endpoint path");
		String path = endpoint;
		if (options.webhookUrl() != null)
			path += "?hf_webhook=" + URLEncoder.encode(options.webhookUrl().toString(), StandardCharsets.UTF_8);
		HttpRequest.Builder request = apiRequest(baseUrl.resolve(path)).header("Content-Type", "application/json");
		if (options.idempotencyKey() != null)
			request.header("Idempotency-Key", options.idempotencyKey());
		if (options.expiresAfterSeconds() != null)
			request.header("Hf-Expires-After", options.expiresAfterSeconds().toString());
		return sendJson(request.POST(HttpRequest.BodyPublishers.ofString(writeJson(input))).build()).thenApply(body -> {
			String id = requiredText(body, "request_id");
			URI statusUrl = trustedUri(requiredText(body, "status_url"));
			URI cancelUrl = trustedUri(requiredText(body, "cancel_url"));
			return new RequestHandle(this, id, statusUrl, cancelUrl);
		});
	}

	public RequestHandle submit(String endpoint, Object input, SubmitOptions options) {
		return await(submitAsync(endpoint, input, options));
	}

	public RequestHandle submit(String endpoint, Object input) {
		return submit(endpoint, input, SubmitOptions.defaults());
	}

	public CompletableFuture<RequestHandle> submitAsync(String endpoint, Object input) {
		return submitAsync(endpoint, input, SubmitOptions.defaults());
	}

	public RequestHandle submit(ModelDescriptor model, Object input) {
		return submit(Objects.requireNonNull(model).id(), input);
	}

	public RequestHandle submit(ModelDescriptor model, Object input, SubmitOptions options) {
		return submit(Objects.requireNonNull(model).id(), input, options);
	}

	public CompletableFuture<RequestHandle> submitAsync(ModelDescriptor model, Object input) {
		return submitAsync(Objects.requireNonNull(model).id(), input);
	}

	public CompletableFuture<RequestHandle> submitAsync(ModelDescriptor model, Object input, SubmitOptions options) {
		return submitAsync(Objects.requireNonNull(model).id(), input, options);
	}

	/** Submit and wait. The callback can persist the ID before local polling begins. */
	public CompletableFuture<RequestStatus> subscribeAsync(String endpoint, Object input, SubmitOptions submitOptions,
			PollingOptions pollingOptions, java.util.function.Consumer<RequestHandle> onAccepted) {
		Objects.requireNonNull(onAccepted);
		var result = new CompletableFuture<RequestStatus>();
		var current = new AtomicReference<CompletableFuture<?>>();
		result.whenComplete((value, failure) -> {
			if (result.isCancelled()) {
				var operation = current.get();
				if (operation != null)
					operation.cancel(true);
			}
		});
		var submission = submitAsync(endpoint, input, submitOptions);
		current.set(submission);
		submission.whenComplete((handle, error) -> {
			if (error != null) {
				result.completeExceptionally(error);
				return;
			}
			try {
				onAccepted.accept(handle);
				if (result.isDone())
					return;
				var waiting = handle.waitForResultAsync(pollingOptions);
				current.set(waiting);
				if (result.isDone()) {
					waiting.cancel(true);
					return;
				}
				waiting.whenComplete((status, failure) -> {
					if (failure != null)
						result.completeExceptionally(failure);
					else
						result.complete(status);
				});
			}
			catch (Exception e) {
				result.completeExceptionally(e);
			}
		});
		if (result.isCancelled())
			submission.cancel(true);
		return result;
	}

	public RequestStatus subscribe(String endpoint, Object input, SubmitOptions submitOptions,
			PollingOptions pollingOptions, java.util.function.Consumer<RequestHandle> onAccepted) {
		return await(subscribeAsync(endpoint, input, submitOptions, pollingOptions, onAccepted));
	}

	public CompletableFuture<RequestStatus> subscribeAsync(String endpoint, Object input) {
		return subscribeAsync(endpoint, input, SubmitOptions.defaults(), PollingOptions.defaults(), ignored -> {
		});
	}

	public RequestStatus subscribe(String endpoint, Object input) {
		return await(subscribeAsync(endpoint, input));
	}

	public RequestHandle request(String requestId) {
		if (requestId == null || !requestId.matches("[A-Za-z0-9_-]+"))
			throw new IllegalArgumentException("Invalid request ID");
		return new RequestHandle(this, requestId, baseUrl.resolve("requests/" + requestId + "/status"),
				baseUrl.resolve("requests/" + requestId + "/cancel"));
	}

	public CompletableFuture<RequestStatus> statusAsync(String id) {
		return request(id).statusAsync();
	}

	public RequestStatus status(String id) {
		return await(statusAsync(id));
	}

	CompletableFuture<RequestStatus> statusAsync(String id, URI url) {
		HttpRequest request = apiRequest(url).GET().build();
		CompletableFuture<RequestStatus> result = new CompletableFuture<>();
		statusCalls.add(result);
		AtomicReference<Future<?>> active = new AtomicReference<>();
		result.whenComplete((value, error) -> {
			statusCalls.remove(result);
			Future<?> current = active.get();
			if (current != null && !current.isDone())
				current.cancel(true);
		});
		if (closed) {
			result.completeExceptionally(new CancellationException("Higgsfield client closed"));
			return result;
		}
		class Attempt {

			void run(int number) {
				if (result.isDone())
					return;
				var call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
				active.set(call);
				call.whenComplete((response, failure) -> {
					if (result.isDone())
						return;
					Throwable error = failure instanceof CompletionException && failure.getCause() != null
							? failure.getCause() : failure;
					int code = response == null ? 0 : response.statusCode();
					boolean retryable = error instanceof IOException || code == 429 || code == 500 || code == 502
							|| code == 503 || code == 504;
					if (retryable && number < statusMaxRetries) {
						long delay = retryDelayMillis(response, number);
						try {
							var next = SCHEDULER.schedule(() -> run(number + 1), delay, TimeUnit.MILLISECONDS);
							active.set(next);
							if (result.isDone())
								next.cancel(false);
						}
						catch (RejectedExecutionException e) {
							result.completeExceptionally(e);
						}
						return;
					}
					if (error != null) {
						result.completeExceptionally(error);
						return;
					}
					if (code < 200 || code >= 300) {
						result.completeExceptionally(httpError(response));
						return;
					}
					try {
						JsonNode body = JSON.readTree(response.body());
						result.complete(new RequestStatus(id, requiredText(body, "status"), body));
					}
					catch (Exception e) {
						result.completeExceptionally(e);
					}
				});
				if (result.isDone() && !call.isDone())
					call.cancel(true);
			}

		}
		new Attempt().run(0);
		return result;
	}

	private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
		if (response != null) {
			String header = response.headers().firstValue("Retry-After").orElse("");
			try {
				long seconds = Long.parseLong(header);
				if (seconds >= 0)
					return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
			}
			catch (NumberFormatException ignored) {
				try {
					var instant = java.time.ZonedDateTime
						.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
						.toInstant();
					return Math.max(0, Duration.between(java.time.Instant.now(), instant).toMillis());
				}
				catch (java.time.DateTimeException ignoredDate) {
				}
			}
		}
		long ceiling = Math.min(10_000, 250L << attempt);
		return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
	}

	CompletableFuture<Void> cancelAsync(URI url) {
		return send(apiRequest(url).POST(HttpRequest.BodyPublishers.noBody()).build()).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> cancelAsync(String id) {
		return request(id).cancelAsync();
	}

	public void cancel(String id) {
		await(cancelAsync(id));
	}

	/** Delete a finished request's inputs and outputs on the server. */
	public CompletableFuture<JsonNode> deleteAsync(String id) {
		request(id); // validate the identifier
		return sendJson(apiRequest(baseUrl.resolve("requests/" + id)).DELETE().build());
	}

	public JsonNode delete(String id) {
		return await(deleteAsync(id));
	}

	/**
	 * Authenticated GET for auxiliary endpoints such as model-specific preset catalogs.
	 */
	public CompletableFuture<JsonNode> getAsync(String path, Map<String, String> query) {
		if (path == null || !path.matches("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*"))
			throw new IllegalArgumentException("Invalid endpoint path");
		var queryString = new StringBuilder();
		Objects.requireNonNull(query).forEach((key, value) -> {
			queryString.append(queryString.isEmpty() ? '?' : '&');
			queryString.append(URLEncoder.encode(Objects.requireNonNull(key), StandardCharsets.UTF_8));
			queryString.append('=');
			queryString.append(URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8));
		});
		return sendJson(apiRequest(baseUrl.resolve(path + queryString)).GET().build());
	}

	public JsonNode get(String path, Map<String, String> query) {
		return await(getAsync(path, query));
	}

	public HiggsfieldAgents agents() {
		return new HiggsfieldAgents(this);
	}

	CompletableFuture<JsonNode> agentJson(String method, String path, Object input) {
		var request = apiRequest(baseUrl.resolve("v1/agent/" + path));
		if ("GET".equals(method))
			return sendJson(request.GET().build());
		request.header("Content-Type", "application/json");
		return sendJson(request.POST(HttpRequest.BodyPublishers.ofString(writeJson(input))).build());
	}

	CompletableFuture<Void> agentPostEmpty(String path) {
		return send(apiRequest(baseUrl.resolve("v1/agent/" + path)).POST(HttpRequest.BodyPublishers.noBody()).build())
			.thenApply(ignored -> null);
	}

	CompletableFuture<Void> uploadAgentMedia(URI upload, String contentType, Path path) {
		if (!"https".equalsIgnoreCase(upload.getScheme())
				&& !("http".equalsIgnoreCase(upload.getScheme()) && "http".equalsIgnoreCase(baseUrl.getScheme())))
			throw new IllegalArgumentException("Upload URL must use HTTPS");
		try {
			var request = HttpRequest.newBuilder(upload)
				.timeout(requestTimeout)
				.header("Content-Type", contentType)
				.PUT(HttpRequest.BodyPublishers.ofFile(path))
				.build();
			return send(request).thenApply(ignored -> null);
		}
		catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	public CompletableFuture<RequestStatus> waitForResultAsync(String id, PollingOptions options) {
		return waitForResultAsync(request(id), options);
	}

	CompletableFuture<RequestStatus> waitForResultAsync(RequestHandle handle, PollingOptions options) {
		Objects.requireNonNull(options);
		CompletableFuture<RequestStatus> result = new CompletableFuture<>();
		waits.add(result);
		if (closed) {
			waits.remove(result);
			return CompletableFuture.failedFuture(new CancellationException("Higgsfield client closed"));
		}
		AtomicReference<Future<?>> work = new AtomicReference<>();
		ScheduledFuture<?> deadline;
		try {
			deadline = SCHEDULER.schedule(
					() -> result.completeExceptionally(
							new TimeoutException("Timed out waiting for request " + handle.requestId())),
					options.timeout().toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (RejectedExecutionException e) {
			waits.remove(result);
			result.completeExceptionally(e);
			return result;
		}
		result.whenComplete((value, failure) -> {
			waits.remove(result);
			deadline.cancel(false);
			Future<?> current = work.get();
			if (current != null)
				current.cancel(true);
		});
		class Poll implements Runnable {

			public void run() {
				if (result.isDone())
					return;
				CompletableFuture<RequestStatus> call = handle.statusAsync();
				work.set(call);
				if (result.isDone()) {
					call.cancel(true);
					return;
				}
				call.whenComplete((status, failure) -> {
					if (result.isDone())
						return;
					if (failure != null) {
						result.completeExceptionally(failure);
						return;
					}
					try {
						options.onStatus().accept(status);
						if (!status.isKnown())
							throw new HiggsfieldException(
									"Unknown status '" + status.status() + "' for request " + handle.requestId(), 0,
									status.body().toString());
						if (status.isTerminal())
							result.complete(status);
						else {
							ScheduledFuture<?> next = SCHEDULER.schedule(this, options.interval().toNanos(),
									TimeUnit.NANOSECONDS);
							work.set(next);
							if (result.isDone())
								next.cancel(false);
						}
					}
					catch (Exception e) {
						result.completeExceptionally(e);
					}
				});
			}

		}
		new Poll().run();
		return result;
	}

	public RequestStatus waitForResult(String id, PollingOptions options) {
		return await(waitForResultAsync(id, options));
	}

	public CompletableFuture<String> uploadAsync(Path path, String contentType) {
		try {
			return uploadAsync(HttpRequest.BodyPublishers.ofFile(path), contentType);
		}
		catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	public CompletableFuture<String> uploadAsync(byte[] data, String contentType) {
		return uploadAsync(HttpRequest.BodyPublishers.ofByteArray(Objects.requireNonNull(data)), contentType);
	}

	private CompletableFuture<String> uploadAsync(HttpRequest.BodyPublisher data, String contentType) {
		return sendJson(
				apiRequest(baseUrl.resolve("files/generate-upload-url")).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(writeJson(Map.of("content_type", contentType))))
					.build())
			.thenCompose(slot -> {
				URI upload = URI.create(requiredText(slot, "upload_url"));
				if (!"https".equalsIgnoreCase(upload.getScheme()) && !("http".equalsIgnoreCase(upload.getScheme())
						&& "http".equalsIgnoreCase(baseUrl.getScheme())))
					throw new IllegalArgumentException("Upload URL must use HTTPS");
				HttpRequest.Builder request = HttpRequest.newBuilder(upload).timeout(requestTimeout);
				JsonNode headers = slot.path("upload_headers");
				if (headers.isObject() && !headers.isEmpty())
					headers.fields().forEachRemaining(e -> request.header(e.getKey(), e.getValue().asText()));
				else
					request.header("Content-Type", contentType);
				return send(request.PUT(data).build()).thenApply(ignored -> requiredText(slot, "public_url"));
			});
	}

	public String upload(Path path, String contentType) {
		return await(uploadAsync(path, contentType));
	}

	public String upload(byte[] data, String contentType) {
		return await(uploadAsync(data, contentType));
	}

	private CompletableFuture<JsonNode> sendJson(HttpRequest request) {
		return send(request).thenApply(response -> {
			try {
				return JSON.readTree(response.body());
			}
			catch (IOException e) {
				throw new HiggsfieldException("Invalid JSON response", e);
			}
		});
	}

	private CompletableFuture<HttpResponse<String>> send(HttpRequest request) {
		if (closed)
			return CompletableFuture.failedFuture(new CancellationException("Higgsfield client closed"));
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() < 200 || response.statusCode() >= 300)
				throw httpError(response);
			return response;
		});
	}

	private static HiggsfieldException httpError(HttpResponse<String> response) {
		return new HiggsfieldException("Higgsfield HTTP error " + response.statusCode(), response.statusCode(),
				response.body(), response.headers().firstValue("X-Correlation-ID").orElse(null));
	}

	private HttpRequest.Builder apiRequest(URI uri) {
		return HttpRequest.newBuilder(trustedUri(uri.toString()))
			.timeout(requestTimeout)
			.header("Authorization", "Key " + credentials)
			.header("Accept", "application/json")
			.header("User-Agent", "higgsfield-java-sdk/0.1");
	}

	private URI trustedUri(String value) {
		URI uri = URI.create(value);
		if (!uri.isAbsolute())
			uri = baseUrl.resolve(uri);
		if (!Objects.equals(uri.getScheme(), baseUrl.getScheme()) || !Objects.equals(uri.getHost(), baseUrl.getHost())
				|| effectivePort(uri) != effectivePort(baseUrl) || uri.getUserInfo() != null
				|| uri.getFragment() != null)
			throw new IllegalArgumentException("Untrusted API URL");
		return uri;
	}

	private static int effectivePort(URI uri) {
		return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
	}

	private static URI normalizeBase(URI value) {
		Objects.requireNonNull(value);
		if ((!"https".equalsIgnoreCase(value.getScheme()) && !"http".equalsIgnoreCase(value.getScheme()))
				|| value.getHost() == null || value.getUserInfo() != null || value.getQuery() != null
				|| value.getFragment() != null)
			throw new IllegalArgumentException("Invalid base URL");
		return URI.create(value.toString().endsWith("/") ? value.toString() : value + "/");
	}

	static String requiredText(JsonNode body, String field) {
		if (body == null || !body.path(field).isTextual() || body.path(field).asText().isBlank())
			throw new HiggsfieldException("Missing response field: " + field, 0, null);
		return body.path(field).asText();
	}

	private static String writeJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Unable to serialize request", e);
		}
	}

	static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new HiggsfieldException("Interrupted while waiting for Higgsfield", e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime)
				throw runtime;
			throw new HiggsfieldException("Higgsfield operation failed", cause);
		}
	}

	@Override
	public void close() {
		closed = true;
		waits.forEach(wait -> wait.completeExceptionally(new CancellationException("Higgsfield client closed")));
		statusCalls.forEach(call -> call.completeExceptionally(new CancellationException("Higgsfield client closed")));
	}

}
