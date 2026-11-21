package io.github.mityushin.higgsfield;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Agent session and media operations. Raw response JSON retains new server fields. */
public final class HiggsfieldAgents {

	private final HiggsfieldClient client;

	HiggsfieldAgents(HiggsfieldClient client) {
		this.client = client;
	}

	private static String id(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_-]+"))
			throw new IllegalArgumentException("Invalid agent identifier");
		return value;
	}

	public CompletableFuture<JsonNode> createSessionAsync(Map<String, ?> config) {
		return client.agentJson("POST", "sessions", Map.of("config", Objects.requireNonNull(config)));
	}

	public JsonNode createSession(Map<String, ?> config) {
		return HiggsfieldClient.await(createSessionAsync(config));
	}

	public CompletableFuture<String> sendAsync(String sessionId, String content) {
		return client
			.agentJson("POST", "sessions/" + id(sessionId) + "/messages",
					Map.of("content", Objects.requireNonNull(content)))
			.thenApply(json -> HiggsfieldClient.requiredText(json, "message_id"));
	}

	public String send(String sessionId, String content) {
		return HiggsfieldClient.await(sendAsync(sessionId, content));
	}

	public CompletableFuture<JsonNode> messagesAsync(String sessionId, String after) {
		String query = after == null ? "" : "?after=" + URLEncoder.encode(id(after), StandardCharsets.UTF_8);
		return client.agentJson("GET", "sessions/" + id(sessionId) + "/messages" + query, null);
	}

	public JsonNode messages(String sessionId, String after) {
		return HiggsfieldClient.await(messagesAsync(sessionId, after));
	}

	public CompletableFuture<Void> interruptAsync(String sessionId) {
		return client.agentPostEmpty("sessions/" + id(sessionId) + "/interrupt");
	}

	public void interrupt(String sessionId) {
		HiggsfieldClient.await(interruptAsync(sessionId));
	}

	public CompletableFuture<String> uploadAsync(Path file, String extension, String type) {
		if (extension == null || !extension.matches("[A-Za-z0-9]+"))
			throw new IllegalArgumentException("Invalid media extension");
		if (!java.util.Set.of("image", "video", "audio", "file").contains(type))
			throw new IllegalArgumentException("Invalid media type");
		return client.agentJson("POST", "media", Map.of("extension", extension, "type", type)).thenCompose(slot -> {
			String mediaId = id(HiggsfieldClient.requiredText(slot, "id"));
			String url = HiggsfieldClient.requiredText(slot, "url");
			return client
				.uploadAgentMedia(URI.create(HiggsfieldClient.requiredText(slot, "upload_url")),
						HiggsfieldClient.requiredText(slot, "content_type"), file)
				.thenCompose(ignored -> client.agentJson("POST", "media/" + mediaId + "/confirm", Map.of("type", type)))
				.thenApply(confirm -> {
					if (!"uploaded".equals(confirm.path("status").asText()))
						throw new HiggsfieldException("Agent media was not confirmed", 0, null);
					return url;
				});
		});
	}

	public String upload(Path file, String extension, String type) {
		return HiggsfieldClient.await(uploadAsync(file, extension, type));
	}

	/**
	 * A terminal assistant message, or an awaiting-input state if no question handler was
	 * supplied.
	 */
	public record TurnResult(String status, JsonNode message) {
	}

	public CompletableFuture<TurnResult> runAsync(String sessionId, String content, Duration timeout,
			Function<JsonNode, String> onQuestion) {
		id(sessionId);
		Objects.requireNonNull(timeout);
		if (timeout.isNegative() || timeout.isZero())
			throw new IllegalArgumentException("Timeout must be positive");
		var result = new CompletableFuture<TurnResult>();
		result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		class Turn {

			void send(String text) {
				if (result.isDone())
					return;
				sendAsync(sessionId, text).whenComplete((messageId, error) -> {
					if (error != null)
						result.completeExceptionally(error);
					else
						poll(messageId, 2000);
				});
			}

			void poll(String after, long delay) {
				if (result.isDone())
					return;
				CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
					if (result.isDone())
						return;
					messagesAsync(sessionId, after).whenComplete((state, error) -> {
						if (result.isDone())
							return;
						if (error != null) {
							result.completeExceptionally(error);
							return;
						}
						try {
							JsonNode messages = state.path("messages");
							JsonNode latest = null;
							JsonNode terminal = null;
							if (messages.isArray())
								for (JsonNode message : messages) {
									if (!"assistant".equals(message.path("role").asText()))
										continue;
									latest = message;
									if ("completed".equals(message.path("status").asText())
											|| "failed".equals(message.path("status").asText()))
										terminal = message;
								}
							if (terminal != null) {
								result.complete(new TurnResult(terminal.path("status").asText(), terminal));
							}
							else if ("awaiting_input".equals(state.path("status").asText())) {
								if (onQuestion == null)
									result.complete(new TurnResult("awaiting_input", latest));
								else
									send(Objects.requireNonNull(onQuestion.apply(latest), "question answer"));
							}
							else
								poll(after, Math.min(10_000, delay * 3 / 2));
						}
						catch (Exception e) {
							result.completeExceptionally(e);
						}
					});
				});
			}

		}
		new Turn().send(content);
		return result;
	}

	public TurnResult run(String sessionId, String content, Duration timeout, Function<JsonNode, String> onQuestion) {
		return HiggsfieldClient.await(runAsync(sessionId, content, timeout, onQuestion));
	}

}
