package org.webmind.apidoc;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.webmind.apidoc.endpoint.OpenApiEndpoint;
import org.webmind.apidoc.generator.OpenApiGenerator;
import org.webmind.apidoc.scanner.WebMindMappingScanner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMindOpenApiConfigurationTest {
    @Test
    void exposesGeneratedDocumentByDefault() {
        try (var context = new AnnotationConfigApplicationContext(Application.class)) {
            assertTrue(context.getBean(OpenApiEndpoint.class).apiDocs().contains("\"openapi\":\"3.1.0\""));
        }
    }

    @Test
    void hostEnvironmentCanDisableDocumentGenerationAndEndpoint() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("host-environment", Map.of("WEBMIND_APIDOC_ENABLED", "false")));

            context.register(Application.class, OpenApiEndpoint.class, TestEndpoint.class);
            context.refresh();

            assertTrue(context.getBeansOfType(WebMindMappingScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenApiGenerator.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenApiEndpoint.class).isEmpty());
            assertNotNull(context.getBean(TestEndpoint.class));
        }
    }
}
