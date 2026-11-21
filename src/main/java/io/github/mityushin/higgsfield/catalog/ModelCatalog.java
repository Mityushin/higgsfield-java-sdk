package io.github.mityushin.higgsfield.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/** Offline catalog bundled with this version of the SDK. */
public final class ModelCatalog {

	private static final String ROOT = "/higgsfield/models/";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final ModelCatalog BUNDLED = load();

	private final Map<String, ModelDescriptor> models;

	private ModelCatalog(Map<String, ModelDescriptor> models) {
		this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
	}

	public static ModelCatalog bundled() {
		return BUNDLED;
	}

	public List<ModelDescriptor> list() {
		return List.copyOf(models.values());
	}

	public List<ModelDescriptor> list(ModelType output) {
		Objects.requireNonNull(output);
		return models.values().stream().filter(model -> model.output() == output).toList();
	}

	public Optional<ModelDescriptor> find(String id) {
		return Optional.ofNullable(models.get(Objects.requireNonNull(id)));
	}

	public ModelDescriptor get(String id) {
		return find(id)
			.orElseThrow(() -> new NoSuchElementException("Model is not in the bundled Higgsfield catalog: " + id));
	}

	private static ModelCatalog load() {
		try {
			JsonNode index = read("index.json");
			if (index.path("formatVersion").asInt() != 1 || !index.path("entries").isArray())
				throw new IllegalStateException("Invalid Higgsfield model catalog index");
			Map<String, ModelDescriptor> models = new LinkedHashMap<>();
			for (JsonNode entry : index.path("entries")) {
				String resource = entry.asText();
				if (!resource.matches("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*\\.json") || resource.contains(".."))
					throw new IllegalStateException("Invalid catalog resource");
				JsonNode model = read(resource);
				String id = required(model, "id");
				if (!(id + ".json").equals(resource))
					throw new IllegalStateException("Catalog entry does not match its model id: " + resource);
				JsonNode schema = model.path("schema");
				if (!schema.isObject() || !schema.path("properties").isObject())
					throw new IllegalStateException("Invalid schema for " + id);
				var descriptor = new ModelDescriptor(id, required(model, "name"), required(model, "operation"),
						ModelType.valueOf(required(model, "output")), URI.create(required(model, "documentation")),
						java.time.LocalDate.parse(required(model, "verifiedAt")),
						model.path("schemaComplete").asBoolean(false), schema);
				if (models.putIfAbsent(id, descriptor) != null)
					throw new IllegalStateException("Duplicate model id: " + id);
			}
			return new ModelCatalog(models);
		}
		catch (IOException | IllegalArgumentException e) {
			throw new IllegalStateException("Unable to load bundled Higgsfield model catalog", e);
		}
	}

	private static JsonNode read(String path) throws IOException {
		try (InputStream stream = ModelCatalog.class.getResourceAsStream(ROOT + path)) {
			if (stream == null)
				throw new IOException("Missing model catalog resource: " + path);
			return JSON.readTree(stream);
		}
	}

	private static String required(JsonNode node, String field) {
		if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
			throw new IllegalStateException("Missing catalog field: " + field);
		return node.path(field).asText();
	}

}
