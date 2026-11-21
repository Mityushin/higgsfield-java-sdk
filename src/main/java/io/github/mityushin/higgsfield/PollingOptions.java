package io.github.mityushin.higgsfield;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

public record PollingOptions(Duration interval, Duration timeout, Consumer<RequestStatus> onStatus) {
	public PollingOptions {
		Objects.requireNonNull(interval);
		Objects.requireNonNull(timeout);
		if (interval.isZero() || interval.isNegative() || timeout.isZero() || timeout.isNegative())
			throw new IllegalArgumentException("Polling interval and timeout must be positive");
		if (onStatus == null)
			onStatus = ignored -> {
			};
	}

	public static PollingOptions defaults() {
		return new PollingOptions(Duration.ofSeconds(3), Duration.ofMinutes(10), null);
	}
}
