package org.webmind.apidoc.scanner;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mind.framework.annotation.Mapping;
import org.mind.framework.cache.AbstractCache;
import org.mind.framework.web.container.spring.SpringContainerAware;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.webmind.apidoc.generator.OpenApiGenerator;
import org.webmind.apidoc.parser.OpenApiAnnotationParser;
import org.webmind.apidoc.resolver.WebMindRouteResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 启动时发现 Controller Bean 和 @Mapping Method
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Slf4j
public class WebMindMappingScanner implements SmartInitializingSingleton {
    private final ApplicationContext applicationContext;
    private final OpenApiAnnotationParser annotationParser;
    private final WebMindRouteResolver routeResolver;
    private final OpenApiGenerator generator;
    private final List<MappingMetadata> mappings;

    public WebMindMappingScanner(ApplicationContext applicationContext,
                                 OpenApiAnnotationParser annotationParser,
                                 WebMindRouteResolver routeResolver,
                                 OpenApiGenerator generator) {

        this.mappings = new ArrayList<>(64);
        this.applicationContext = applicationContext;
        this.annotationParser = annotationParser;
        this.routeResolver = routeResolver;
        this.generator = generator;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 1. 扫描 Controller 的 @Mapping
        applicationContext.getBeansWithAnnotation(Controller.class).forEach(this::scanController);

        // 2. 解析真实路由, 读取注解、解析参数及返回值，生成每个路径和 HTTP 方法的 Operation。
        List<WebMindRouteResolver.ResolvedRoute> routes = routeResolver.resolve(getMappings());
        OpenAPI document = generator.createDocument();
        Set<String> operationIdSet = getOperationIdSet(document);

        // 3. 读取 @Operation 注解、解析参数/返回值、生成 OpenAPI Operation
        for (WebMindRouteResolver.ResolvedRoute route : routes) {
            Operation operation = annotationParser.buildOperation(route, document.getComponents());
            if (operation == null)
                continue;

            PathItem path = document.getPaths().computeIfAbsent(route.path(), ignored -> new PathItem());
            if (path.readOperationsMap().containsKey(route.httpMethod()))
                throw new IllegalArgumentException("Duplicate OpenAPI route " + route.httpMethod() + " " + route.path());

            operation.setOperationId(newOperationId(route, operation, operationIdSet));
            path.operation(route.httpMethod(), operation);
        }

        // 4. 文档组装完成后，一次性生成并缓存 JSON。
        generator.generate(document);

        operationIdSet.clear();
        mappings.clear();
        routes = null;
    }

    public List<MappingMetadata> getMappings() {
        return List.copyOf(mappings);
    }

    private void scanController(String beanName, Object bean) {
        // 处理 Spring AOP 代理
        Class<?> controllerClass = AopProxyUtils.ultimateTargetClass(bean);

        // 遍历 Controller 的方法，包括继承的方法
        ReflectionUtils.doWithMethods(
                controllerClass,
                method -> scanMethod(
                        beanName,
                        controllerClass,
                        method
                ),
                SpringContainerAware::isValidMethod
        );
    }

    private void scanMethod(String beanName, Class<?> controllerClass, Method method) {
        // 查找 WebMind @Mapping
        Mapping mapping = SpringContainerAware.resolveAnnotation(controllerClass, method, Mapping.class);
        if (SpringContainerAware.noneMappingMethod(method, mapping))
            return;

        // 读取 @Mapping 中的 method
        String[] methods = Arrays.stream(mapping.method())
                .map(Enum::name)
                .toArray(String[]::new);

        MappingMetadata metadata = new MappingMetadata(
                beanName,
                controllerClass,
                method,
                mapping.value(),
                methods,
                mapping.async().value()
        );

        mappings.add(metadata);
    }

    private Set<String> getOperationIdSet(OpenAPI document) {
        Set<String> idSet = new HashSet<>();
        for (PathItem path : document.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (operation.getOperationId() != null && !idSet.add(operation.getOperationId()))
                    throw new IllegalArgumentException("Duplicate operationId: " + operation.getOperationId());
            }
        }
        return idSet;
    }

    private String newOperationId(WebMindRouteResolver.ResolvedRoute route, Operation operation, Set<String> ids) {
        String base = operation.getOperationId();

        if (StringUtils.isEmpty(base)) {
            base = new StringJoiner(AbstractCache.CACHE_DELIMITER)
                    .add(route.metadata().method().getName())
                    .add(route.httpMethod().name().toLowerCase(Locale.ROOT))
                    .toString();
        }

        String id = base;
        for (int suffix = 2; !ids.add(id); ++suffix) {
            id = base + AbstractCache.CACHE_DELIMITER + suffix;
        }

        return id;
    }
}
