package org.webmind.apidoc.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.webmind.apidoc.WebMindOpenApiConfiguration;
import org.webmind.apidoc.generator.OpenApiGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiRequestBodyTest {
    @Test
    void includesAnnotatedRequestBodyInGeneratedDocument() throws JsonProcessingException {
        try (var context = new AnnotationConfigApplicationContext(WebMindOpenApiConfiguration.class)) {
            String json = context.getBean(OpenApiGenerator.class).getJson();
            JsonNode document = Json31.mapper().readTree(json);
            JsonNode create = document.at("/paths/~1create/post");
            JsonNode requestBody = create.path("requestBody");

            assertTrue(requestBody.isObject(), "POST /create should describe its annotated request body");
            assertTrue(requestBody.path("required").asBoolean());
            assertEquals("#/components/schemas/Payload",
                    requestBody.at("/content/application~1json/schema/$ref").asText());
            assertEquals("string", document.at("/components/schemas/Payload/properties/name/type").asText());
            assertTrue(create.path("responses").has("201"));

            JsonNode search = document.at("/paths/~1search/get");
            assertTrue(search.isObject());
            assertFalse(search.has("requestBody"));
        }
    }
}
