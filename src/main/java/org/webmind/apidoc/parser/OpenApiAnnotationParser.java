package org.webmind.apidoc.parser;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.ParameterProcessor;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.apache.commons.lang3.StringUtils;
import org.mind.framework.web.container.spring.SpringContainerAware;
import org.mind.framework.web.renderer.Render;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.webmind.apidoc.resolver.OpenApiSchemaResolver;
import org.webmind.apidoc.resolver.WebMindRouteResolver.PathVariable;
import org.webmind.apidoc.resolver.WebMindRouteResolver.ResolvedRoute;
import org.webmind.apidoc.scanner.MappingOperationMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将 Swagger 注解补充到真实的 WebMind 路由上。
 * 路径及 HTTP 方法以路由为准；通过 Action 在方法体读取的参数只接受显式注解。
 *
 * @author: Marcus
 * @date: 2026/11/10
 * @version: 1.0
 */
public class OpenApiAnnotationParser {
    private static final String JSON = "application/json";
    private static final String HTML = "text/html";
    private final OpenApiSchemaResolver schemaResolver;

    public OpenApiAnnotationParser(OpenApiSchemaResolver schemaResolver) {
        this.schemaResolver = Objects.requireNonNull(schemaResolver, "schemaResolver");
    }

    /**
     * 生成 OpenAPI Operation
     * 优先级为 Java 参数注解、方法上的独立注解、@Operation 中的注解、自动推断。
     */
    public Operation buildOperation(Class<?> controllerClass, Method method) {
        // 读取方法上的 @Operation，若无或 Hidden 则抛弃
        io.swagger.v3.oas.annotations.Operation operationAnn =
                SpringContainerAware.resolveAnnotation(controllerClass, method, io.swagger.v3.oas.annotations.Operation.class);

        if (operationAnn == null || isHidden(controllerClass, method, operationAnn))
            return null;

        // 每次解析产生独立的 Operation
        Operation operation = new Operation();

        // 解析 @Operation
        parseOperation(controllerClass, method, operation, operationAnn);

        // 解析参数 @Parameter & @Schema
        parseParameters(controllerClass, method, operation, operationAnn);

        // 解析返回值及 @ApiResponse
        parseResponses(controllerClass, method, operation);

        return operation;
    }

    /**
     * 在路由校验前排除隐藏接口，避免未公开的路由阻止文档生成。
     */
    protected boolean isHidden(Class<?> controllerClass, Method method, io.swagger.v3.oas.annotations.Operation annotation) {
        return AnnotatedElementUtils.hasAnnotation(controllerClass, Hidden.class)
                || AnnotatedElementUtils.hasAnnotation(method, Hidden.class)
                || annotation.hidden();
    }

    /**
     * 解析 @Operation
     */
    protected void parseOperation(Class<?> controllerClass,
                                  Method method,
                                  Operation operation,
                                  io.swagger.v3.oas.annotations.Operation annotation) {
        // 获取类和方法上的所有 tags
        Set<String> tags = SpringContainerAware.parseCollectAnnotation(controllerClass, method, Tag.class).stream()
                .map(Tag::name)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // @Operation 中的 @tags
        Arrays.stream(annotation.tags()).filter(StringUtils::isNotBlank).forEach(tags::add);

        if (!tags.isEmpty())
            operation.setTags(new ArrayList<>(tags));

        operation.summary(StringUtils.defaultIfBlank(annotation.summary(), null))
                .description(StringUtils.defaultIfBlank(annotation.description(), null))
                .operationId(StringUtils.defaultIfBlank(annotation.operationId(), method.getName()))
                .setDeprecated(annotation.deprecated() || method.isAnnotationPresent(Deprecated.class));

        AnnotationsUtils.getExternalDocumentation(annotation.externalDocs(), true).ifPresent(operation::setExternalDocs);
        AnnotationsUtils.getServers(annotation.servers()).ifPresent(operation::setServers);
        AnnotationsUtils.getExtensions(true, annotation.extensions()).forEach(operation::addExtension31);

        // @Operation 中的 @SecurityRequirement
        Arrays.stream(annotation.security())
                .filter(security -> StringUtils.isNotBlank(security.name()))
                .forEach(security ->
                        operation.addSecurityItem(new SecurityRequirement().addList(security.name(), List.of(security.scopes())))
                );
    }

    /**
     * 解析参数 @Parameter & @Schema
     */
    protected void parseParameters(Class<?> controllerClass,
                                   Method method,
                                   Operation operation,
                                   io.swagger.v3.oas.annotations.Operation annotation) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
    }


    private void parseParameters(ResolvedRoute route, io.swagger.v3.oas.annotations.Operation operationAnnotation,
                                 Operation operation, Components components) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        MappingOperationMetadata mapping = route.mapping();
        for (PathVariable variable : route.pathVariables()) {
            Parameter parameter = new Parameter().name(variable.name()).in("path").required(true);
            Type type = parameterType(mapping, variable.parameterIndex());
            parameter.setSchema(schemaResolver.resolve(type, parameterAnnotations(mapping, variable.parameterIndex()), components));
            parameters.put(key(parameter), parameter);
        }
        if (operationAnnotation != null) {
            for (io.swagger.v3.oas.annotations.Parameter annotation : operationAnnotation.parameters()) {
                mergeParameter(parameters, annotation, null, route, components);
            }
        }
        for (io.swagger.v3.oas.annotations.Parameter annotation : collect(mapping, io.swagger.v3.oas.annotations.Parameter.class)) {
            mergeParameter(parameters, annotation, null, route, components);
        }
        for (PathVariable variable : route.pathVariables()) {
            Annotation[] parameterAnnotations = parameterAnnotations(mapping, variable.parameterIndex());
            io.swagger.v3.oas.annotations.Parameter annotation = AnnotationsUtils.getAnnotation(
                    io.swagger.v3.oas.annotations.Parameter.class, parameterAnnotations);
            if (annotation == null || annotation.hidden()) continue;
            Parameter parameter = parameter(annotation, parameterType(mapping, variable.parameterIndex()), components,
                    parameters.get("path:" + variable.name()), parameterAnnotations);
            // 方法实参只能按路由顺序绑定，注解不能将它改成 query 或重命名路径占位符。
            if (parameter.get$ref() != null) {
                throw new IllegalArgumentException("A bound path parameter cannot use @Parameter(ref): " + variable.name());
            }
            parameter.name(variable.name()).in("path").required(true);
            parameters.put(key(parameter), parameter);
        }
        if (!parameters.isEmpty()) operation.setParameters(new ArrayList<>(parameters.values()));
    }

    private void mergeParameter(Map<String, Parameter> parameters, io.swagger.v3.oas.annotations.Parameter annotation,
                                Type type, ResolvedRoute route, Components components) {
        if (!annotation.ref().isBlank()) {
            mergeReferenceParameter(parameters, annotation, route, components);
            return;
        }
        String in = annotation.in() == ParameterIn.DEFAULT
                ? route.pathVariables().stream().anyMatch(variable -> variable.name().equals(annotation.name())) ? "path" : "query"
                : annotation.in().toString();
        String parameterKey = in + ":" + annotation.name();
        if (annotation.hidden()) {
            if (!"path".equals(in)) parameters.remove(parameterKey);
            return;
        }
        if (annotation.name().isBlank()) {
            throw new IllegalArgumentException("Method-level @Parameter requires a name: " + route.mapping().method());
        }
        if ("path".equals(in) && route.pathVariables().stream().noneMatch(variable -> variable.name().equals(annotation.name()))) {
            throw new IllegalArgumentException("@Parameter names a path variable absent from the route: " + annotation.name());
        }
        if (type == null && "path".equals(in)) {
            PathVariable variable = route.pathVariables().stream().filter(value -> value.name().equals(annotation.name())).findFirst().orElseThrow();
            type = parameterType(route.mapping(), variable.parameterIndex());
        }
        Parameter parameter = parameter(annotation, type, components, parameters.get(parameterKey), new Annotation[0]);
        parameter.setIn(in);
        if ("path".equals(in)) parameter.setRequired(true);
        parameters.put(key(parameter), parameter);
    }

    private Parameter parameter(io.swagger.v3.oas.annotations.Parameter annotation, Type type, Components components,
                                Parameter existing, Annotation[] contextAnnotations) {
        List<Annotation> annotations = new ArrayList<>(List.of(annotation));
        if (AnnotationsUtils.hasSchemaAnnotation(annotation.schema())) annotations.add(annotation.schema());
        if (AnnotationsUtils.hasArrayAnnotation(annotation.array())) annotations.add(annotation.array());
        for (Annotation context : contextAnnotations) {
            if (!(context instanceof io.swagger.v3.oas.annotations.Parameter)) annotations.add(context);
        }
        if (type == null) type = ParameterProcessor.getParameterType(annotation);
        Schema<?> previousSchema = existing == null ? null : existing.getSchema();
        Content previousContent = existing == null ? null : existing.getContent();
        Components local = new Components();
        // Swagger 负责参数元数据；类型与 Content 仍由本项目统一按 3.1 处理。
        Parameter parameter = ParameterProcessor.applyAnnotations(existing, type, annotations, local, null, null, null, true);
        if (parameter.get$ref() != null) return parameter;
        if (annotation.examples().length == 1 && annotation.examples()[0].name().isBlank()) {
            AnnotationsUtils.getExample(annotation.examples()[0], true).ifPresent(example -> parameter.setExample(example.getValue()));
        }
        if (annotations.size() > 1 || previousSchema == null && previousContent == null) {
            parameter.setSchema(schemaResolver.resolve(type, annotations.toArray(Annotation[]::new), components));
            parameter.setContent(null);
        } else {
            parameter.setSchema(previousSchema);
            parameter.setContent(previousContent);
        }
        annotationContent(annotation.content(), components).ifPresent(content -> parameter.content(content).schema(null));
        return parameter;
    }

    private void parseRequestBody(MappingOperationMetadata mapping, io.swagger.v3.oas.annotations.Operation operationAnnotation,
                                  Operation operation, Components components) {
        io.swagger.v3.oas.annotations.parameters.RequestBody annotation = find(mapping, io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        if (annotation == null && operationAnnotation != null) annotation = operationAnnotation.requestBody();
        if (annotation == null || annotation.ref().isBlank() && annotation.description().isBlank()
                && annotation.content().length == 0 && !annotation.required() && annotation.extensions().length == 0)
            return;
        RequestBody body = new RequestBody();
        if (!annotation.ref().isBlank()) {
            body.set$ref(annotation.ref());
        } else {
            if (!annotation.description().isBlank()) body.setDescription(annotation.description());
            if (annotation.required()) body.setRequired(true);
            body.setContent(annotationContent(annotation.content(), components)
                    .orElseGet(() -> new Content().addMediaType(JSON, new MediaType())));
            AnnotationsUtils.getExtensions(true, annotation.extensions()).forEach(body::addExtension);
        }
        operation.setRequestBody(body);
    }

    private void parseResponses(MappingOperationMetadata mapping, io.swagger.v3.oas.annotations.Operation operationAnnotation,
                                Operation operation, Components components) {
        Map<String, io.swagger.v3.oas.annotations.responses.ApiResponse> annotations = new LinkedHashMap<>();
        if (operationAnnotation != null) {
            for (io.swagger.v3.oas.annotations.responses.ApiResponse annotation : operationAnnotation.responses()) {
                annotations.put(annotation.responseCode(), annotation);
            }
        }
        for (io.swagger.v3.oas.annotations.responses.ApiResponse annotation : collect(mapping, io.swagger.v3.oas.annotations.responses.ApiResponse.class)) {
            annotations.put(annotation.responseCode(), annotation);
        }
        ApiResponses responses = new ApiResponses();
        if (annotations.isEmpty()) {
            responses.addApiResponse("200", new ApiResponse().description("Successful response").content(returnContent(mapping, components)));
        }
        for (var entry : annotations.entrySet()) {
            String code = entry.getKey();
            var annotation = entry.getValue();
            if (!code.matches("default|[1-5](?:[0-9]{2}|XX)")) {
                throw new IllegalArgumentException("Invalid OpenAPI response code '" + code + "' on " + mapping.method());
            }
            ApiResponse response = new ApiResponse();
            responses.addApiResponse(code, response);
            if (!annotation.ref().isBlank()) {
                response.set$ref(annotation.ref());
                continue;
            }
            response.setDescription(annotation.description().isBlank() ? "Response" : annotation.description());
            Content content = annotationContent(annotation.content(), components).orElse(null);
            if (annotation.useReturnTypeSchema()) {
                Content inferred = returnContent(mapping, components);
                if (content == null) content = inferred;
                else if (inferred != null) for (MediaType media : content.values()) {
                    if (media.getSchema() == null) media.setSchema(inferred.values().iterator().next().getSchema());
                }
            }
            response.setContent(content);
            Map<String, io.swagger.v3.oas.models.headers.Header> headers = annotationHeaders(annotation.headers(), components);
            if (!headers.isEmpty()) response.setHeaders(headers);
            var links = AnnotationsUtils.getLinks(annotation.links(), true);
            if (!links.isEmpty()) response.setLinks(links);
            AnnotationsUtils.getExtensions(true, annotation.extensions()).forEach(response::addExtension);
        }
        operation.setResponses(responses);
    }

    private Content returnContent(MappingOperationMetadata mapping, Components components) {
        Method method = mapping.method();
        Type type = GenericTypeResolver.resolveType(method.getGenericReturnType(), mapping.controllerClass());
        Class<?> rawType = ResolvableType.forType(type).resolve(method.getReturnType());
        if (rawType == void.class || rawType == Void.class || Render.class.isAssignableFrom(rawType)) return null;
        io.swagger.v3.oas.annotations.media.Schema schemaAnnotation = find(mapping, io.swagger.v3.oas.annotations.media.Schema.class);
        Schema<?> schema = schemaResolver.resolve(type, schemaAnnotation == null ? new Annotation[0] : new Annotation[]{schemaAnnotation}, components);
        if (schema == null) return null;
        boolean text = rawType.isPrimitive() || rawType == String.class || rawType == Character.class
                || rawType == Boolean.class || Number.class.isAssignableFrom(rawType);
        return new Content().addMediaType(text ? HTML : JSON, new MediaType().schema(schema));
    }

    private Type parameterType(MappingOperationMetadata mapping, int index) {
        return GenericTypeResolver.resolveType(mapping.method().getGenericParameterTypes()[index], mapping.controllerClass());
    }

    private String key(Parameter parameter) {
        return parameter.get$ref() == null ? parameter.getIn() + ":" + parameter.getName() : "$ref:" + parameter.get$ref();
    }

    private String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private <A extends Annotation> A find(MappingOperationMetadata mapping, Class<A> annotationType) {
        return SpringContainerAware.resolveAnnotation(mapping.controllerClass(), mapping.method(), annotationType);
    }

    private <A extends Annotation> List<A> collect(MappingOperationMetadata mapping, Class<A> annotationType) {
        return SpringContainerAware.parseCollectAnnotation(mapping.controllerClass(), mapping.method(), annotationType);
    }

    private Optional<Content> annotationContent(io.swagger.v3.oas.annotations.media.Content[] annotations, Components components) {
        Content content = new Content();
        for (io.swagger.v3.oas.annotations.media.Content annotation : annotations) {
            // Swagger 的工具会直接覆写 Components，逐个隔离解析后交给 resolver 检查重名。
            Components local = new Components();
            Optional<Content> parsed = AnnotationsUtils.getContent(new io.swagger.v3.oas.annotations.media.Content[]{annotation},
                    null, new String[]{JSON}, null, local, null, true);
            if (annotation.schema().implementation() != Void.class) {
                Schema<?> schema = contextualSchema(annotation.schema(), local);
                parsed.ifPresent(value -> value.values().forEach(media -> media.setSchema(schema)));
            }
            schemaResolver.mergeComponents(local, components);
            parsed.ifPresent(value -> value.forEach(content::addMediaType));
        }
        return content.isEmpty() ? Optional.empty() : Optional.of(content);
    }

    private Map<String, io.swagger.v3.oas.models.headers.Header> annotationHeaders(
            io.swagger.v3.oas.annotations.headers.Header[] annotations, Components components) {
        Map<String, io.swagger.v3.oas.models.headers.Header> headers = new LinkedHashMap<>();
        for (io.swagger.v3.oas.annotations.headers.Header annotation : annotations) {
            Components local = new Components();
            Optional<io.swagger.v3.oas.models.headers.Header> parsed = AnnotationsUtils.getHeader(annotation, local, null, true);
            if (parsed.isPresent() && annotation.schema().implementation() != Void.class) {
                parsed.get().setSchema(contextualSchema(annotation.schema(), local));
            }
            schemaResolver.mergeComponents(local, components);
            parsed.ifPresent(value -> headers.put(annotation.name(), value));
        }
        return headers;
    }

    private Schema<?> contextualSchema(io.swagger.v3.oas.annotations.media.Schema annotation, Components utilityComponents) {
        Components canonical = new Components();
        Schema<?> schema = schemaResolver.resolve(annotation.implementation(), new Annotation[]{annotation}, canonical);
        // Core 的注解工具把使用处的 description 等写进共享 DTO，替换为 resolver 的标准定义。
        // 使用处的信息由返回的 3.1 $ref 兄弟字段保留，媒体示例和 encoding 不受影响。
        if (canonical.getSchemas() != null) canonical.getSchemas().forEach(utilityComponents::addSchemas);
        return schema;
    }

    private void mergeReferenceParameter(Map<String, Parameter> parameters,
                                         io.swagger.v3.oas.annotations.Parameter annotation,
                                         ResolvedRoute route, Components components) {
        if (annotation.hidden()) return;
        Parameter referenced = referencedParameter(annotation.ref(), components, new LinkedHashSet<>());
        if (referenced == null) {
            if (!route.pathVariables().isEmpty()) {
                throw new IllegalArgumentException("Cannot verify an external parameter reference against path variables: " + annotation.ref());
            }
            parameters.put("$ref:" + annotation.ref(), new Parameter().$ref(annotation.ref()));
            return;
        }
        if (referenced.getName() == null || referenced.getIn() == null) {
            throw new IllegalArgumentException("Referenced parameter requires name and in: " + annotation.ref());
        }
        if ("path".equals(referenced.getIn())) {
            if (route.pathVariables().stream().noneMatch(variable -> variable.name().equals(referenced.getName()))) {
                throw new IllegalArgumentException("Referenced path parameter is absent from the route: " + referenced.getName());
            }
            if (!Boolean.TRUE.equals(referenced.getRequired())) {
                throw new IllegalArgumentException("Referenced path parameter must be required: " + annotation.ref());
            }
        }
        parameters.put(key(referenced), new Parameter().$ref(annotation.ref()));
    }

    private Parameter referencedParameter(String ref, Components components, Set<String> visited) {
        String prefix = "#/components/parameters/";
        if (!ref.startsWith(prefix)) return null;
        if (!visited.add(ref)) throw new IllegalArgumentException("Circular parameter reference: " + ref);
        String name = ref.substring(prefix.length()).replace("~1", "/").replace("~0", "~");
        Parameter parameter = components.getParameters() == null ? null : components.getParameters().get(name);
        if (parameter == null) throw new IllegalArgumentException("Unknown parameter reference: " + ref);
        return parameter.get$ref() == null ? parameter : referencedParameter(parameter.get$ref(), components, visited);
    }

    private Annotation[] parameterAnnotations(MappingOperationMetadata mapping, int index) {
        Map<Class<? extends Annotation>, Annotation> annotations = new LinkedHashMap<>();
        Set<Class<?>> hierarchy = new LinkedHashSet<>(List.of(mapping.controllerClass()));
        hierarchy.addAll(ClassUtils.getAllInterfacesForClassAsSet(mapping.controllerClass()));
        for (Class<?> parent = mapping.controllerClass().getSuperclass(); parent != null; parent = parent.getSuperclass())
            hierarchy.add(parent);
        for (Class<?> type : hierarchy)
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.isBridge() || candidate.isSynthetic() || !sameMethod(candidate, mapping.method(), mapping.controllerClass()))
                    continue;
                for (Annotation annotation : candidate.getParameterAnnotations()[index])
                    annotations.putIfAbsent(annotation.annotationType(), annotation);
            }
        for (Annotation annotation : mapping.method().getParameterAnnotations()[index]) {
            annotations.putIfAbsent(annotation.annotationType(), annotation);
        }
        return annotations.values().toArray(Annotation[]::new);
    }

    /**
     * 解析方法参数与 @Parameter 注解
     */
    private void parseParameters(Method method, Operation operation) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < parameters.length; ++i) {
            java.lang.reflect.Parameter param = parameters[i];
            Annotation[] annotations = parameterAnnotations[i];

            // 查找 @Parameter 注解
            Parameter paramAnn = Arrays.stream(annotations)
                    .filter(ann -> ann instanceof Parameter)
                    .map(ann -> (Parameter) ann)
                    .findFirst()
                    .orElse(null);

            Parameter oasParameter = null;
            if (paramAnn != null) {
                oasParameter = AnnotationsUtils.getParameter(paramAnn).orElse(null);
            }

            if (oasParameter == null) {
                oasParameter = new Parameter();
                oasParameter.setName(param.getName());
            }

            // 如果未指定 Schema，通过 ModelConverters 自动生成对应 Java 类型的 Schema
            if (oasParameter.getSchema() == null) {
                Map<String, Schema> schemas = ModelConverters.getInstance().read(param.getType());
                if (schemas != null && !schemas.isEmpty()) {
                    oasParameter.setSchema(schemas.values().iterator().next());
                }
            }

            operation.addParametersItem(oasParameter);
        }
    }

    private boolean sameMethod(Method candidate, Method method, Class<?> controller) {
        if (!candidate.getName().equals(method.getName()) || candidate.getParameterCount() != method.getParameterCount())
            return false;

        for (int i = 0; i < method.getParameterCount(); ++i) {
            if (ResolvableType.forMethodParameter(candidate, i, controller).resolve()
                    != ResolvableType.forMethodParameter(method, i, controller).resolve()) {
                return false;
            }
        }

        return true;
    }
}
