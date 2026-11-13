package org.webmind.apidoc.resolver;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

/**
 * Resolves Java types and Swagger schema annotations using native OpenAPI 3.1 rules.
 * Referenced models belong to the supplied document, never to a global schema cache.
 *
 * @author: Marcus
 * @date: 2026/11/10
 * @version: 1.0
 */
public class OpenApiSchemaResolver {

    private final ModelConverters converters = new ModelConverters(true);

    /**
     * @return an inline schema or a component reference; {@code null} for void
     */
    public Schema<?> resolve(Type type, Components components) {
        return resolve(type, null, components);
    }

    /**
     * Resolves a parameter or response type, retaining its generic type arguments.
     * Swagger Core handles field annotations, enums, containers and recursive DTOs.
     *
     * @param annotations annotations on the parameter or return value, including {@code @Schema}
     * @param components  the components of the document receiving the returned schema
     * @return an inline schema or a component reference; {@code null} for void
     */
    public synchronized Schema<?> resolve(Type type, Annotation[] annotations, Components components) {
        Objects.requireNonNull(type, "Java type must not be null");
        Objects.requireNonNull(components, "OpenAPI components must not be null");
        if (type == void.class || type == Void.class) {
            return null;
        }

        io.swagger.v3.oas.annotations.media.Schema schemaAnnotation = AnnotationsUtils.getSchemaAnnotation(annotations);
        boolean explicitImplementation = schemaAnnotation != null && schemaAnnotation.implementation() != Void.class;
        Type effectiveType = explicitImplementation ? schemaAnnotation.implementation() : type;
        Components localComponents = new Components();
        // Applying an implementation equal to the current target through ModelResolver's
        // override branch reenters the same converter context and is treated as a cycle.
        AnnotatedType annotatedType = new AnnotatedType(effectiveType)
                .ctxAnnotations(annotations)
                .skipOverride(explicitImplementation)
                .resolveAsRef(true)
                .components(localComponents);

        ResolvedSchema resolved = converters.resolveAsResolvedSchema(annotatedType);
        if (resolved == null || resolved.schema == null) {
            throw new IllegalArgumentException("Cannot resolve OpenAPI schema for Java type: " + type.getTypeName());
        }

        if (schemaAnnotation != null && resolved.schema.get$ref() != null) {
            // Context descriptions and constraints describe this use of the type, not
            // every use of its shared component. Swagger's direct conversion puts them
            // on the model; resolve the canonical model and retain them as 3.1 siblings.
            localComponents = new Components();
            if (!schemaAnnotation.ref().isBlank()) {
                resolved = new ResolvedSchema();
                resolved.schema = new JsonSchema().$ref(schemaAnnotation.ref());
            } else {
                Type canonicalType = schemaAnnotation.implementation() == Void.class
                        ? type : schemaAnnotation.implementation();
                Annotation[] canonicalAnnotations =
                        Arrays.stream(annotations)
                                .filter(annotation -> !(annotation instanceof io.swagger.v3.oas.annotations.media.Schema))
                                .toArray(Annotation[]::new);
                AnnotatedType canonical = new AnnotatedType(canonicalType)
                        .ctxAnnotations(canonicalAnnotations)
                        .resolveAsRef(true)
                        .resolveEnumAsRef(schemaAnnotation.enumAsRef())
                        .components(localComponents);
                if (!schemaAnnotation.name().isBlank()) canonical.name(schemaAnnotation.name());
                resolved = converters.resolveAsResolvedSchema(canonical);
            }
            if (resolved.schema == null) {
                throw new IllegalArgumentException("Cannot resolve OpenAPI schema for Java type: " + type.getTypeName());
            }
            resolved.schema = AnnotationsUtils.getSchemaFromAnnotation(schemaAnnotation, localComponents, null,
                    true, resolved.schema).orElse(resolved.schema);
        }
        if (resolved.referencedSchemas != null) {
            resolved.referencedSchemas.forEach(localComponents::addSchemas);
        }
        mergeComponents(localComponents, components);
        return resolved.schema;
    }

    /**
     * Merges schemas produced by Swagger annotation utilities into a document.
     * All names are validated before any schema is added; existing equal models
     * are retained. Other component sections are not changed.
     */
    public synchronized void mergeComponents(Components source, Components target) {
        Objects.requireNonNull(source, "Source components must not be null");
        Objects.requireNonNull(target, "Target components must not be null");
        if (source.getSchemas() != null) {
            // Validate the entire batch before modifying the document. Different DTOs may
            // otherwise silently overwrite each other when their simple names match.
            source.getSchemas().forEach((name, schema) -> {
                Schema<?> existing = target.getSchemas() == null ? null : target.getSchemas().get(name);
                if (existing != null && !existing.equals(schema)) {
                    throw new IllegalArgumentException("Conflicting OpenAPI schema name '" + name
                            + "'; give the DTOs unique @Schema(name = ...) values");
                }
            });
            source.getSchemas().forEach((name, schema) -> {
                if (target.getSchemas() == null || !target.getSchemas().containsKey(name)) {
                    target.addSchemas(name, schema);
                }
            });
        }
    }
}
