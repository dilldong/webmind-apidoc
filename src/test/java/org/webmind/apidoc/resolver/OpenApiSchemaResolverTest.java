package org.webmind.apidoc.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiSchemaResolverTest {

    private final OpenApiSchemaResolver resolver = new OpenApiSchemaResolver();

    @Test
    void resolvesScalarTypesAndEnumsInOpenApi31Mode() {
        Components components = new Components();
        JsonNode integer = json(resolver.resolve(long.class, components));
        assertEquals("integer", integer.path("type").asText());
        assertEquals("int64", integer.path("format").asText());
        assertEquals("boolean", json(resolver.resolve(boolean.class, components)).path("type").asText());
        JsonNode status = json(resolver.resolve(Status.class, components));
        assertEquals("string", status.path("type").asText());
        assertEquals("ACTIVE", status.path("enum").get(0).asText());
    }

    @Test
    void registersDtoSchemasAndAppliesFieldAnnotations() {
        Components components = new Components();
        Schema<?> reference = resolver.resolve(User.class, components);

        assertEquals("#/components/schemas/User", reference.get$ref());
        JsonNode user = json(components.getSchemas().get("User"));
        assertEquals("A user account", user.path("description").asText());
        assertEquals("Display name", user.path("properties").path("name").path("description").asText());
        assertEquals("name", user.path("required").get(0).asText());
        assertFalse(user.path("properties").has("secret"));
    }

    @Test
    void resolvesArraysCollectionsMapsAndNestedGenerics() throws Exception {
        Components components = new Components();
        Schema<?> array = resolver.resolve(User[].class, components);
        assertEquals("array", json(array).path("type").asText());
        assertEquals("#/components/schemas/User", array.getItems().get$ref());

        Schema<?> list = resolver.resolve(fieldType("users"), components);
        assertEquals("#/components/schemas/User", list.getItems().get$ref());
        Schema<?> map = resolver.resolve(fieldType("usersByName"), components);
        Schema<?> mapValues = assertInstanceOf(Schema.class, map.getAdditionalProperties());
        assertEquals("#/components/schemas/User", mapValues.get$ref());

        Schema<?> envelope = resolver.resolve(fieldType("response"), components);
        Schema<?> envelopeModel = component(envelope, components);
        Schema<?> data = envelopeModel.getProperties().get("data");
        assertEquals("array", json(data).path("type").asText());
        assertEquals("#/components/schemas/User", data.getItems().get$ref());
    }

    @Test
    void appliesContextSchemaAnnotations() throws Exception {
        var field = Types.class.getDeclaredField("identifier");
        Schema<?> schema = resolver.resolve(field.getGenericType(), field.getAnnotations(), new Components());
        JsonNode identifier = json(schema);

        assertEquals("Identifier", identifier.path("description").asText());
        assertEquals(1, identifier.path("minimum").asInt());
        assertEquals(99, identifier.path("maximum").asInt());
    }

    @Test
    void keepsContextualDtoDescriptionsOnReferenceSiblings() throws Exception {
        Components components = new Components();
        var first = Types.class.getDeclaredField("firstUser");
        var second = Types.class.getDeclaredField("secondUser");

        Schema<?> firstSchema = resolver.resolve(first.getGenericType(), first.getAnnotations(), components);
        Schema<?> secondSchema = resolver.resolve(second.getGenericType(), second.getAnnotations(), components);

        assertEquals("#/components/schemas/User", firstSchema.get$ref());
        assertEquals(firstSchema.get$ref(), secondSchema.get$ref());
        assertEquals("First use", json(firstSchema).path("description").asText());
        assertEquals("Second use", json(secondSchema).path("description").asText());
        assertEquals("A user account", components.getSchemas().get("User").getDescription());
        resolver.resolve(User.class, components);
        assertEquals("First use", firstSchema.getDescription());
    }

    @Test
    void resolvesExplicitImplementationWithoutSelfReferentialConverterCycle() throws Exception {
        Components components = new Components();
        var field = Types.class.getDeclaredField("explicitUser");

        Schema<?> sameType = resolver.resolve(User.class, field.getAnnotations(), components);
        Schema<?> overriddenType = resolver.resolve(Object.class, field.getAnnotations(), components);

        assertEquals("#/components/schemas/User", sameType.get$ref());
        assertEquals(sameType.get$ref(), overriddenType.get$ref());
        assertEquals("Explicit implementation", sameType.getDescription());
        assertEquals("Explicit implementation", overriddenType.getDescription());
        assertEquals("A user account", components.getSchemas().get("User").getDescription());
    }

    @Test
    void serializesNativeOpenApi31UnionTypesAndNumericExclusiveMinimum() {
        Components components = new Components();
        Schema<?> reference = resolver.resolve(Native31Dto.class, components);
        JsonNode properties = json(component(reference, components)).path("properties");
        JsonNode nullableName = properties.path("nullableName");
        JsonNode type = nullableName.path("type");

        assertTrue(type.isArray());
        Set<String> types = new HashSet<>();
        type.forEach(value -> types.add(value.asText()));
        assertEquals(Set.of("string", "null"), types);
        assertFalse(nullableName.has("nullable"));

        JsonNode exclusiveMinimum = properties.path("positiveAmount").path("exclusiveMinimum");
        assertTrue(exclusiveMinimum.isNumber());
        assertEquals(1, exclusiveMinimum.asInt());
    }

    @Test
    void registersRecursiveReferencesAndIsolatesDocuments() {
        Components first = new Components();
        Components second = new Components();
        Schema<?> reference = resolver.resolve(Node.class, first);
        resolver.resolve(Node.class, second);

        Schema<?> firstNode = component(reference, first);
        assertEquals(reference.get$ref(), firstNode.getProperties().get("next").get$ref());
        Schema<?> secondNode = component(reference, second);
        assertNotSame(firstNode, secondNode);
        firstNode.setDescription("Changed in the first document");
        assertNotEquals(firstNode.getDescription(), secondNode.getDescription());
    }

    @Test
    void returnsNoSchemaForVoidAndRejectsConflictingComponentNames() {
        Components components = new Components();
        assertNull(resolver.resolve(void.class, components));
        assertNull(resolver.resolve(Void.class, components));
        assertNull(components.getSchemas());

        resolver.resolve(FirstCollision.class, components);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(SecondCollision.class, components));
        assertTrue(error.getMessage().contains("Collision"));
        assertTrue(components.getSchemas().get("Collision").getProperties().containsKey("first"));
    }

    private static Type fieldType(String name) throws NoSuchFieldException {
        return Types.class.getDeclaredField(name).getGenericType();
    }

    private static Schema<?> component(Schema<?> reference, Components components) {
        String name = reference.get$ref().substring("#/components/schemas/".length());
        return components.getSchemas().get(name);
    }

    private static JsonNode json(Schema<?> schema) {
        return Json31.mapper().valueToTree(schema);
    }

    enum Status { ACTIVE, DISABLED }

    @io.swagger.v3.oas.annotations.media.Schema(name = "User", description = "A user account")
    public static class User {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Display name",
                requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        public String name;

        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        public String secret;
    }

    public static class Envelope<T> {
        public T data;
    }

    public static class Node {
        public Node next;
    }

    public static class Native31Dto {
        @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
        public String nullableName;

        @io.swagger.v3.oas.annotations.media.Schema(exclusiveMinimumValue = 1)
        public BigDecimal positiveAmount;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "Collision")
    public static class FirstCollision {
        public String first;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "Collision")
    public static class SecondCollision {
        public int second;
    }

    static class Types {
        List<User> users;
        Map<String, User> usersByName;
        Envelope<List<User>> response;

        @io.swagger.v3.oas.annotations.media.Schema(description = "First use")
        User firstUser;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Second use")
        User secondUser;

        @io.swagger.v3.oas.annotations.media.Schema(implementation = User.class, description = "Explicit implementation")
        Object explicitUser;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Identifier", minimum = "1", maximum = "99")
        long identifier;
    }
}
