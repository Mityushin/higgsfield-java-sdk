package io.github.mityushin.higgsfield;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** The original response remains available for model-specific output and future fields. */
public record RequestStatus(String requestId, String status, JsonNode body) {
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "nsfw", "canceled");
    public boolean isTerminal() { return TERMINAL.contains(status); }
    public boolean isCompleted() { return "completed".equals(status); }
    public boolean isKnown() { return isTerminal() || "queued".equals(status) || "in_progress".equals(status); }
}
