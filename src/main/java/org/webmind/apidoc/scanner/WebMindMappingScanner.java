package org.webmind.apidoc.scanner;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.mind.framework.annotation.Mapping;
import org.mind.framework.util.ReflectionUtils;
import org.mind.framework.web.container.spring.SpringContainerAware;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.webmind.apidoc.generator.OpenApiGenerator;
import org.webmind.apidoc.parser.OpenApiAnnotationParser;
import org.webmind.apidoc.resolver.WebMindRouteResolver;
import org.webmind.apidoc.resolver.WebMindRouteResolver.ResolvedRoute;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final WebMindRouteResolver routeResolver;
    private final OpenApiAnnotationParser annotationParser;
    private final OpenApiGenerator generator;
    private final List<MappingOperationMetadata> mappings = new ArrayList<>();

    public WebMindMappingScanner(ApplicationContext applicationContext,
                                 WebMindRouteResolver routeResolver,
                                 OpenApiAnnotationParser annotationParser,
                                 OpenApiGenerator generator) {
        this.applicationContext = applicationContext;
        this.routeResolver = routeResolver;
        this.annotationParser = annotationParser;
        this.generator = generator;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 1. 扫描 Controller 的 @Mapping。
        mappings.clear();
        applicationContext.getBeansWithAnnotation(Controller.class).forEach(this::scanController);

        // 2. 解析真实路由，隐藏接口不参与文档生成
        List<ResolvedRoute> routes = routeResolver.resolve(getMappings());

        // 3. 读取注解、解析参数及返回值，生成每个路径和 HTTP 方法的 Operation。
        OpenAPI document = generator.createDocument();
        Set<String> operationIds = operationIds(document);
        for (ResolvedRoute route : routes) {
            Operation operation = annotationParser.parse(route, document.getComponents());
            PathItem path = document.getPaths().computeIfAbsent(route.path(), ignored -> new PathItem());
            if (path.readOperationsMap().containsKey(route.httpMethod())) {
                throw new IllegalArgumentException("Duplicate OpenAPI route " + route.httpMethod() + " " + route.path());
            }
            operation.setOperationId(operationId(route, operation, operationIds));
            path.operation(route.httpMethod(), operation);
        }

        // 4. 文档组装完成后，一次性生成并缓存 JSON。
        generator.generate(document);
    }

    private Set<String> operationIds(OpenAPI document) {
        Set<String> ids = new HashSet<>();
        for (PathItem path : document.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (operation.getOperationId() != null && !ids.add(operation.getOperationId())) {
                    throw new IllegalArgumentException("Duplicate operationId: " + operation.getOperationId());
                }
            }
        }
        return ids;
    }

    private String operationId(ResolvedRoute route, Operation operation, Set<String> ids) {
        String base = operation.getOperationId();
        if (base == null || base.isBlank()) {
            base = route.mapping().controllerClass().getSimpleName() + "_" + route.mapping().method().getName()
                    + "_" + route.httpMethod().name().toLowerCase(Locale.ROOT);
        }
        String id = base;
        for (int suffix = 2; !ids.add(id); suffix++) id = base + "_" + suffix;
        return id;
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

        // 读取 @Operation 注解、解析参数/返回值、生成 OpenAPI Operation
        Operation operation = annotationParser.buildOperation(controllerClass, method);
        if(operation == null)
            return;

        // 路径格式转换：将 ${path} 转换成 OpenAPI 标准的 {path}
        String[] pathArray = Arrays.stream(mapping.value())
                .map(path -> {
                    String value = Strings.CS.replace(path, "${", "{");
                    return Strings.CS.replace(value, "}", "}");
                })
                .toArray(String[]::new);

        // 读取 @Mapping 中的 method
        String[] methods = Arrays.stream(mapping.method())
                .map(Enum::name)
                .toArray(String[]::new);

        MappingOperationMetadata metadata = new MappingOperationMetadata(
                beanName,
                controllerClass,
                method,
                pathArray,
                methods,
                mapping.async().value(),
                operation
        );

        mappings.add(metadata);
    }

    public List<MappingOperationMetadata> getMappings() {
        return List.copyOf(mappings);
    }
}
