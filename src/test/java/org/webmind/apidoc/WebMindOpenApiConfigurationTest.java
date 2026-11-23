package org.webmind.apidoc;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.webmind.apidoc.endpoint.OpenApiEndpoint;
import org.webmind.apidoc.endpoint.OpenApiUiEndpoint;
import org.webmind.apidoc.generator.OpenApiGenerator;
import org.webmind.apidoc.scanner.WebMindMappingScanner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMindOpenApiConfigurationTest {
    @Test
    void exposesGeneratedDocumentByDefault() {
        try (var context = new AnnotationConfigApplicationContext(Application.class)) {
            assertTrue(context.getBean(OpenApiEndpoint.class).apiDocs().contains("\"openapi\":\"3.1.0\""));
            OpenApiUiEndpoint ui = context.getBean(OpenApiUiEndpoint.class);
            assertEquals("text/html", ui.page().getContentType());
            assertEquals("text/css", ui.asset("styles").getContentType());
            for (String script : new String[]{"jquery", "openapi", "history", "console"}) {
                assertEquals("text/javascript", ui.asset(script).getContentType());
            }
            assertFalse(context.getBean(OpenApiGenerator.class).getOpenAPI().getPaths().containsKey("/api-docs"));
        }
    }

    @Test
    void hostEnvironmentCanDisableDocumentGenerationAndEndpoint() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("host-environment", Map.of("WEBMIND_APIDOC_ENABLED", "false")));

            context.register(Application.class, OpenApiEndpoint.class, OpenApiUiEndpoint.class, TestEndpoint.class);
            context.refresh();

            assertTrue(context.getBeansOfType(WebMindMappingScanner.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenApiGenerator.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenApiEndpoint.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenApiUiEndpoint.class).isEmpty());
            assertNotNull(context.getBean(TestEndpoint.class));
        }
    }
}
