package org.webmind.apidoc;

import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.webmind.apidoc.generator.OpenApiGenerator;
import org.webmind.apidoc.parser.OpenApiAnnotationParser;
import org.webmind.apidoc.resolver.OpenApiSchemaResolver;
import org.webmind.apidoc.resolver.WebMindRouteResolver;
import org.webmind.apidoc.scanner.WebMindMappingScanner;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "org.webmind.apidoc")
public class WebMindOpenApiConfiguration {

    @Bean
    public WebMindMappingScanner webmindMappingScanner(ApplicationContext applicationContext,
                                                       OpenApiAnnotationParser annotationParser,
                                                       WebMindRouteResolver routeResolver,
                                                       OpenApiGenerator generator) {
        return new WebMindMappingScanner(applicationContext, annotationParser, routeResolver, generator);
    }

    @Bean
    public OpenApiSchemaResolver openApiSchemaResolver() {
        return new OpenApiSchemaResolver();
    }

    @Bean
    public OpenApiAnnotationParser openApiAnnotationParser(OpenApiSchemaResolver schemaResolver) {
        return new OpenApiAnnotationParser(schemaResolver);
    }

    @Bean
    public WebMindRouteResolver routeResolver() {
        return new WebMindRouteResolver();
    }

    @Bean
    public OpenApiGenerator openApiGenerator(ObjectProvider<OpenAPI> definition) {
        return new OpenApiGenerator(definition.getIfAvailable(OpenAPI::new));
    }
}
