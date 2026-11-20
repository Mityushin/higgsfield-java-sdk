package io.github.mityushin.higgsfield;

import io.github.mityushin.higgsfield.catalog.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogTest {
    @Test void listsKnownSeedanceEndpointsAndFiltersOutput() {
        var catalog = ModelCatalog.bundled();
        assertEquals(14, catalog.list().size());
        assertEquals(10, catalog.list(ModelType.VIDEO).size());
        assertEquals(4, catalog.list(ModelType.IMAGE).size());
        var model = catalog.get(HiggsfieldModels.SEEDANCE_2_0_TEXT_TO_VIDEO);
        assertEquals("TEXT_TO_VIDEO", model.operation());
        assertEquals(ModelType.VIDEO, model.output());
        assertFalse(model.schemaComplete());
        assertEquals(15, model.schema().path("properties").path("duration").path("maximum").asInt());
        assertTrue(catalog.find("unknown/model").isEmpty());
        assertThrows(NoSuchElementException.class, () -> catalog.get("unknown/model"));
    }
    @Test void descriptorsAndListsCannotMutateBundledCatalog() {
        var model = ModelCatalog.bundled().get(HiggsfieldModels.SEEDANCE_2_0_TEXT_TO_VIDEO);
        ((com.fasterxml.jackson.databind.node.ObjectNode) model.schema()).remove("properties");
        assertTrue(model.schema().has("properties"));
        assertThrows(UnsupportedOperationException.class, () -> ModelCatalog.bundled().list().clear());
    }
    @Test void entriesHaveUniqueIdsValidDocsAndSchema() {
        var ids = new HashSet<String>();
        for (var model : ModelCatalog.bundled().list()) {
            assertTrue(ids.add(model.id()));
            assertEquals("https", model.documentation().getScheme());
            assertTrue(model.schema().path("properties").isObject());
            assertTrue(model.schema().path("required").isArray());
            for (var required : model.schema().path("required")) assertTrue(model.schema().path("properties").has(required.asText()));
        }
    }
    @Test void everyNamedConstantIsPresentInCatalog() throws Exception {
        for (var field : HiggsfieldModels.class.getFields()) {
            if (field.getType() == String.class) assertTrue(ModelCatalog.bundled().find((String) field.get(null)).isPresent(), field.getName());
        }
    }
}
