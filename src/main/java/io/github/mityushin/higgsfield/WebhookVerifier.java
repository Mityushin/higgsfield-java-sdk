package io.github.mityushin.higgsfield;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Verifies Standard Webhooks signatures against the exact, unmodified HTTP body. */
public final class WebhookVerifier {

	private final List<byte[]> keys;

	private final Clock clock;

	private final Duration tolerance;

	public WebhookVerifier(List<String> secrets, Clock clock, Duration tolerance) {
		Objects.requireNonNull(secrets);
		if (secrets.isEmpty())
			throw new IllegalArgumentException("At least one webhook secret is required");
		this.keys = secrets.stream().map(secret -> {
			if (secret == null || !secret.startsWith("whsec_"))
				throw new IllegalArgumentException("Invalid webhook secret prefix");
			byte[] key = Base64.getDecoder().decode(secret.substring(6));
			if (key.length == 0)
				throw new IllegalArgumentException("Empty webhook secret");
			return key;
		}).toList();
		this.clock = Objects.requireNonNull(clock);
		this.tolerance = Objects.requireNonNull(tolerance);
		if (tolerance.isNegative() || tolerance.isZero())
			throw new IllegalArgumentException("Tolerance must be positive");
	}

	public WebhookVerifier(String secret) {
		this(List.of(secret), Clock.systemUTC(), Duration.ofMinutes(5));
	}

	public boolean verify(String id, String timestamp, String signatures, byte[] rawBody) {
		if (id == null || id.isBlank() || timestamp == null || signatures == null || rawBody == null)
			return false;
		final Instant sent;
		try {
			sent = Instant.ofEpochSecond(Long.parseLong(timestamp));
		}
		catch (RuntimeException e) {
			return false;
		}
		if (Duration.between(sent, clock.instant()).abs().compareTo(tolerance) > 0)
			return false;
		byte[] prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
		for (byte[] key : keys) {
			final byte[] expected;
			try {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(key, "HmacSHA256"));
				mac.update(prefix);
				expected = mac.doFinal(rawBody);
			}
			catch (GeneralSecurityException e) {
				throw new IllegalStateException("HMAC-SHA256 unavailable", e);
			}
			for (String item : signatures.trim().split("\\s+")) {
				if (!item.startsWith("v1,"))
					continue;
				try {
					if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(item.substring(3))))
						return true;
				}
				catch (IllegalArgumentException ignored) {
					/* Other signatures may still be valid. */ }
			}
		}
		return false;
	}

}
