package io.github.mityushin.higgsfield;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebhookVerifierTest {

	@Test
	void verifiesExactBodyTimestampAndRotatedSecrets() throws Exception {
		byte[] key = "secret-key".getBytes(StandardCharsets.UTF_8);
		String secret = "whsec_" + Base64.getEncoder().encodeToString(key);
		String timestamp = "1700000000";
		byte[] body = "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		mac.update(("event-1." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
		String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
		var verifier = new WebhookVerifier(
				List.of("whsec_" + Base64.getEncoder().encodeToString("old".getBytes()), secret),
				Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC), Duration.ofMinutes(5));
		assertTrue(verifier.verify("event-1", timestamp, "v1,invalid " + signature, body));
		assertFalse(verifier.verify("event-1", timestamp, signature, "{}".getBytes(StandardCharsets.UTF_8)));
		assertFalse(verifier.verify("event-1", "1699999000", signature, body));
		assertFalse(verifier.verify("event-1", timestamp, "v2,abc", body));
	}

}
