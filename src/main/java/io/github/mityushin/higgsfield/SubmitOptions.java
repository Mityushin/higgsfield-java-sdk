package io.github.mityushin.higgsfield;

import java.net.URI;

public record SubmitOptions(URI webhookUrl, String idempotencyKey, Long expiresAfterSeconds) {
	public SubmitOptions(URI webhookUrl, String idempotencyKey) {
		this(webhookUrl, idempotencyKey, null);
	}

	public SubmitOptions {
		if (webhookUrl != null && (!"https".equalsIgnoreCase(webhookUrl.getScheme()) || webhookUrl.getHost() == null
				|| webhookUrl.getUserInfo() != null))
			throw new IllegalArgumentException("Webhook URL must be an HTTPS URL without credentials");
		if (expiresAfterSeconds != null && expiresAfterSeconds <= 0)
			throw new IllegalArgumentException("Retention must be positive");
	}

	public static SubmitOptions defaults() {
		return new SubmitOptions(null, null, null);
	}
}
