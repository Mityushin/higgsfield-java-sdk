package io.github.mityushin.higgsfield.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A versioned, documented endpoint snapshot. The schema may describe only known fields.
 */
public final class ModelDescriptor {

	private final String id;

	private final String name;

	private final String operation;

	private final ModelType output;

	private final URI documentation;

	private final LocalDate verifiedAt;

	private final boolean schemaComplete;

	private final JsonNode schema;

	ModelDescriptor(String id, String name, String operation, ModelType output, URI documentation, LocalDate verifiedAt,
			boolean schemaComplete, JsonNode schema) {
		this.id = Objects.requireNonNull(id);
		this.name = Objects.requireNonNull(name);
		this.operation = Objects.requireNonNull(operation);
		this.output = Objects.requireNonNull(output);
		this.documentation = Objects.requireNonNull(documentation);
		this.verifiedAt = Objects.requireNonNull(verifiedAt);
		this.schemaComplete = schemaComplete;
		this.schema = Objects.requireNonNull(schema).deepCopy();
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String operation() {
		return operation;
	}

	public ModelType output() {
		return output;
	}

	public URI documentation() {
		return documentation;
	}

	public LocalDate verifiedAt() {
		return verifiedAt;
	}

	public boolean schemaComplete() {
		return schemaComplete;
	}

	/** Returns a defensive copy of the documented JSON Schema snapshot. */
	public JsonNode schema() {
		return schema.deepCopy();
	}

}
