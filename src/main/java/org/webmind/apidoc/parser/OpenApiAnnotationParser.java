package org.webmind.apidoc.parser;

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
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.mind.framework.util.JsonUtils;
import org.mind.framework.web.container.spring.SpringContainerAware;
import org.mind.framework.web.renderer.Render;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.webmind.apidoc.resolver.OpenApiSchemaResolver;
import org.webmind.apidoc.resolver.WebMindRouteResolver;
import org.webmind.apidoc.scanner.MappingMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将 Swagger 注解补充到真实的 WebMind 路由上。
 * 路径及 HTTP 方法以 @Mapping 路由为准；在方法体读取的参数只接受显式注解。
 *
 * @author: Marcus
 * @date: 2026/11/10
 * @version: 1.0
 */
public class OpenApiAnnotationParser {
    private static final Pattern PATTERN = Pattern.compile("default|[1-5](?:[0-9]{2}|XX)");
    private static final String PATH = "path";
    private static final String QUERY = "query";

    // 负责处理复杂 Schema 嵌套与校验约束的解析器
    private final OpenApiSchemaResolver schemaResolver;

    public OpenApiAnnotationParser(OpenApiSchemaResolver schemaResolver) {
        this.schemaResolver = schemaResolver;
    }

    /**
     * 生成 OpenAPI Operation
     * 优先级为 Java 参数注解、方法上的独立注解、@Operation 中的注解、自动推断。
     */
    public Operation buildOperation(WebMindRouteResolver.ResolvedRoute route, Components components) {
        // 读取方法上的 @Operation，若无或 Hidden 则抛弃
        io.swagger.v3.oas.annotations.Operation operationAnn =
                SpringContainerAware.resolveAnnotation(
                        route.metadata().controllerClass(),
                        route.metadata().method(),
                        io.swagger.v3.oas.annotations.Operation.class
                );

        if (operationAnn == null || isHidden(route.metadata().controllerClass(), route.metadata().method(), operationAnn))
            return null;

        // 每次解析产生独立的 Operation
        Operation operation = new Operation();

        // 解析 @Operation
        parseOperation(route.metadata(), operation, operationAnn);

        // 解析参数 @Parameter & @Schema
        parseParameters(route, operation, operationAnn, components);

        // 解析 @RequestBody
        parseRequestBody(operationAnn.requestBody(), operation, components);

        // 解析返回值及 @ApiResponse
        parseResponses(route.metadata(), operation, operationAnn, components);

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
    protected void parseOperation(MappingMetadata metadata,
                                  Operation operation,
                                  io.swagger.v3.oas.annotations.Operation annotation) {
        // 获取类和方法上的所有 tags
        Set<String> tags = SpringContainerAware.parseCollectAnnotation(
                        metadata.controllerClass(),
                        metadata.method(),
                        Tag.class)
                .stream()
                .map(Tag::name)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // @Operation 中的 @tags
        Arrays.stream(annotation.tags()).filter(StringUtils::isNotBlank).forEach(tags::add);

        if (!tags.isEmpty())
            operation.setTags(new ArrayList<>(tags));

        operation.summary(StringUtils.defaultIfBlank(annotation.summary(), null))
                .description(StringUtils.defaultIfBlank(annotation.description(), null))
                .operationId(StringUtils.defaultIfBlank(annotation.operationId(), metadata.method().getName()))
                .setDeprecated(annotation.deprecated() || metadata.method().isAnnotationPresent(Deprecated.class));

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

    private void parseParameters(WebMindRouteResolver.ResolvedRoute route,
                                 Operation operation,
                                 io.swagger.v3.oas.annotations.Operation operationAnnotation,
                                 Components components) {

        Map<String, Parameter> parameters = new LinkedHashMap<>();
        MappingMetadata metadata = route.metadata();

        for (WebMindRouteResolver.PathVariable variable : route.pathVariables()) {
            Parameter parameter = new Parameter().name(variable.name()).in(PATH).required(true);
            Type type = parameterType(metadata, variable.parameterIndex());
            parameter.setSchema(schemaResolver.resolve(type, parameterAnnotations(metadata, variable.parameterIndex()), components));
            parameters.put(key(parameter), parameter);
        }

        if (operationAnnotation != null) {
            for (io.swagger.v3.oas.annotations.Parameter annotation : operationAnnotation.parameters()) {
                mergeParameter(parameters, annotation, null, route, components);
            }
        }

        List<io.swagger.v3.oas.annotations.Parameter> annotations =
                collect(metadata, io.swagger.v3.oas.annotations.Parameter.class);

        for (io.swagger.v3.oas.annotations.Parameter annotation : annotations) {
            mergeParameter(parameters, annotation, null, route, components);
        }

        for (WebMindRouteResolver.PathVariable variable : route.pathVariables()) {
            Annotation[] parameterAnnotations = parameterAnnotations(metadata, variable.parameterIndex());

            io.swagger.v3.oas.annotations.Parameter annotation =
                    AnnotationsUtils.getAnnotation(
                            io.swagger.v3.oas.annotations.Parameter.class,
                            parameterAnnotations
                    );

            if (annotation == null || annotation.hidden())
                continue;

            Parameter parameter = resolveAnnotatedParameter(
                    annotation,
                    parameterType(metadata, variable.parameterIndex()),
                    components,
                    parameters.get("path:" + variable.name()),
                    parameterAnnotations);

            // 方法实参只能按路由顺序绑定，注解不能将它改成 query 或重命名路径占位符。
            if (parameter.get$ref() != null)
                throw new IllegalArgumentException("A bound path parameter cannot use @Parameter(ref): " + variable.name());

            parameter.name(variable.name()).in(PATH).required(true);
            parameters.put(key(parameter), parameter);
        }

        if (!parameters.isEmpty())
            operation.setParameters(new ArrayList<>(parameters.values()));
    }

    private void mergeParameter(Map<String, Parameter> parameters,
                                io.swagger.v3.oas.annotations.Parameter annotation,
                                Type type,
                                WebMindRouteResolver.ResolvedRoute route,
                                Components components) {
        if (!annotation.ref().isBlank()) {
            mergeReferenceParameter(parameters, annotation, route, components);
            return;
        }

        Predicate<WebMindRouteResolver.PathVariable> predicate = variable -> variable.name().equals(annotation.name());
        String in = annotation.in() == ParameterIn.DEFAULT
                ? route.pathVariables().stream().anyMatch(predicate) ? PATH : QUERY
                : annotation.in().toString();

        String parameterKey = in + JsonUtils.COLON_SEPARATOR + annotation.name();
        if (annotation.hidden()) {
            if (!PATH.equals(in))
                parameters.remove(parameterKey);
            return;
        }

        if (StringUtils.isBlank(annotation.name()))
            throw new IllegalArgumentException("Method-level @Parameter requires a name: " + route.metadata().method().getName());

        boolean noneMatch = route.pathVariables().stream().noneMatch(predicate);

        if (PATH.equals(in) && noneMatch)
            throw new IllegalArgumentException("@Parameter names a path variable absent from the route: " + annotation.name());

        if (type == null && PATH.equals(in)) {
            WebMindRouteResolver.PathVariable variable = route.pathVariables().stream()
                    .filter(predicate)
                    .findFirst()
                    .orElseThrow();

            type = parameterType(route.metadata(), variable.parameterIndex());
        }

        Parameter parameter = resolveAnnotatedParameter(
                annotation, type, components, parameters.get(parameterKey), new Annotation[0]
        );

        parameter.setIn(in);

        if (PATH.equals(in))
            parameter.setRequired(true);

        parameters.put(key(parameter), parameter);
    }

    private Parameter resolveAnnotatedParameter(io.swagger.v3.oas.annotations.Parameter annotation,
                                                Type type,
                                                Components components,
                                                Parameter existing,
                                                Annotation[] contextAnnotations) {

        List<Annotation> annotations = new ArrayList<>(List.of(annotation));
        if (AnnotationsUtils.hasSchemaAnnotation(annotation.schema()))
            annotations.add(annotation.schema());

        if (AnnotationsUtils.hasArrayAnnotation(annotation.array()))
            annotations.add(annotation.array());

        for (Annotation context : contextAnnotations) {
            if (!(context instanceof io.swagger.v3.oas.annotations.Parameter))
                annotations.add(context);
        }

        if (type == null)
            type = ParameterProcessor.getParameterType(annotation);

        Schema<?> previousSchema = existing == null ? null : existing.getSchema();
        Content previousContent = existing == null ? null : existing.getContent();
        Components local = new Components();

        // Swagger 负责参数元数据；类型与 Content 仍由本项目统一按 3.1 处理。
        Parameter parameter = ParameterProcessor.applyAnnotations(
                existing, type, annotations, local, null, null, null, true
        );

        if (parameter.get$ref() != null)
            return parameter;

        // 解析自定义 Examples
        if (annotation.examples().length == 1 && !annotation.examples()[0].name().isBlank()) {
            AnnotationsUtils.getExample(annotation.examples()[0], true)
                    .ifPresent(example -> parameter.setExample(example.getValue()));
        }

        if (annotations.size() > 1 || previousSchema == null && previousContent == null) {
            parameter.setSchema(schemaResolver.resolve(type, annotations.toArray(Annotation[]::new), components));
            parameter.setContent(null);
        } else {
            parameter.setSchema(previousSchema);
            parameter.setContent(previousContent);
        }

        // 解析专属 Content 媒体类型
        annotationContent(annotation.content(), components)
                .ifPresent(content -> parameter.content(content).schema(null));

        return parameter;
    }

    private void parseRequestBody(io.swagger.v3.oas.annotations.parameters.RequestBody annotation,
                                  Operation operation,
                                  Components components) {

        if (StringUtils.isNotBlank(annotation.ref())) {
            operation.setRequestBody(new RequestBody().$ref(annotation.ref()));
            return;
        }

        String description = StringUtils.defaultIfBlank(annotation.description(), null);
        Map<String, Object> extensions = AnnotationsUtils.getExtensions(true, annotation.extensions());
        if (annotation.content().length == 0 && description == null && !annotation.required() && extensions.isEmpty())
            return;

        Content content = annotationContent(annotation.content(), components)
                .orElseGet(() -> new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE, new MediaType()));

        RequestBody body = new RequestBody().description(description).content(content);
        if (annotation.required())
            body.setRequired(true);

        extensions.forEach(body::addExtension31);
        operation.setRequestBody(body);
    }

    private void parseResponses(MappingMetadata mapping,
                                Operation operation,
                                io.swagger.v3.oas.annotations.Operation operationAnnotation,
                                Components components) {

        Map<String, io.swagger.v3.oas.annotations.responses.ApiResponse> annotations = new LinkedHashMap<>();
        if (operationAnnotation != null) {
            for (io.swagger.v3.oas.annotations.responses.ApiResponse annotation : operationAnnotation.responses()) {
                annotations.put(annotation.responseCode(), annotation);
            }
        }

        for (io.swagger.v3.oas.annotations.responses.ApiResponse annotation :
                collect(mapping, io.swagger.v3.oas.annotations.responses.ApiResponse.class)) {
            annotations.put(annotation.responseCode(), annotation);
        }

        ApiResponses responses = new ApiResponses();
        if (annotations.isEmpty()) {
            responses.addApiResponse(
                    String.valueOf(HttpServletResponse.SC_OK),
                    new ApiResponse().description(HttpStatus.OK.getReasonPhrase()).content(returnContent(mapping, components))
            );
        }

        for (var entry : annotations.entrySet()) {
            String code = entry.getKey();
            var annotation = entry.getValue();

            if (!PATTERN.matcher(code).matches()) {
                throw new IllegalArgumentException("Invalid OpenAPI response code '" + code + "' on " + mapping.method());
            }

            ApiResponse response = new ApiResponse();
            responses.addApiResponse(code, response);
            if (StringUtils.isNotBlank(annotation.ref())) {
                response.set$ref(annotation.ref());
                continue;
            }

            response.setDescription(annotation.description().isBlank() ? "Response" : annotation.description());
            Content content = annotationContent(annotation.content(), components).orElse(null);

            if (annotation.useReturnTypeSchema()) {
                Content inferred = returnContent(mapping, components);
                if (content == null)
                    content = inferred;
                else if (inferred != null) {
                    for (MediaType media : content.values()) {
                        if (media.getSchema() == null)
                            media.setSchema(inferred.values().iterator().next().getSchema());
                    }
                }
            }

            response.setContent(content);
            Map<String, io.swagger.v3.oas.models.headers.Header> headers = annotationHeaders(annotation.headers(), components);

            if (!headers.isEmpty())
                response.setHeaders(headers);

            var links = AnnotationsUtils.getLinks(annotation.links(), true);
            if (!links.isEmpty())
                response.setLinks(links);

            AnnotationsUtils.getExtensions(true, annotation.extensions()).forEach(response::addExtension);
        }

        operation.setResponses(responses);
    }

    private Content returnContent(MappingMetadata mapping, Components components) {
        Method method = mapping.method();
        Type type = GenericTypeResolver.resolveType(method.getGenericReturnType(), mapping.controllerClass());
        Class<?> rawType = ResolvableType.forType(type).resolve(method.getReturnType());

        if (rawType == void.class || rawType == Void.class || Render.class.isAssignableFrom(rawType))
            return null;

        io.swagger.v3.oas.annotations.media.Schema schemaAnnotation =
                find(mapping, io.swagger.v3.oas.annotations.media.Schema.class);

        Schema<?> schema = schemaResolver.resolve(
                type,
                schemaAnnotation == null ? new Annotation[0] : new Annotation[]{schemaAnnotation}, components
        );

        if (schema == null)
            return null;

        boolean text = rawType.isPrimitive() || rawType == String.class || rawType == Character.class
                || rawType == Boolean.class || Number.class.isAssignableFrom(rawType);

        return new Content().addMediaType(
                text ? org.springframework.http.MediaType.TEXT_HTML_VALUE : org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                new MediaType().schema(schema)
        );
    }

    private Type parameterType(MappingMetadata mapping, int index) {
        Type[] types = mapping.method().getGenericParameterTypes();
        if (ArrayUtils.isEmpty(types))
            return null;

        return GenericTypeResolver.resolveType(types[index], mapping.controllerClass());
    }

    private String key(Parameter parameter) {
        return parameter.get$ref() == null ?
                parameter.getIn() + JsonUtils.COLON_SEPARATOR + parameter.getName() :
                "$ref:" + parameter.get$ref();
    }

    private <A extends Annotation> A find(MappingMetadata mapping, Class<A> annotationType) {
        return SpringContainerAware.resolveAnnotation(mapping.controllerClass(), mapping.method(), annotationType);
    }

    private <A extends Annotation> List<A> collect(MappingMetadata mapping, Class<A> annotationType) {
        return SpringContainerAware.parseCollectAnnotation(mapping.controllerClass(), mapping.method(), annotationType);
    }

    private Optional<Content> annotationContent(io.swagger.v3.oas.annotations.media.Content[] annotations, Components components) {
        Content content = new Content();

        for (io.swagger.v3.oas.annotations.media.Content annotation : annotations) {
            // Swagger 的工具会直接覆写 Components，逐个隔离解析后交给 resolver 检查重名。
            Components local = new Components();
            Optional<Content> parsed = AnnotationsUtils.getContent(
                    new io.swagger.v3.oas.annotations.media.Content[]{annotation},
                    null,
                    new String[]{org.springframework.http.MediaType.APPLICATION_JSON_VALUE},
                    null, local, null, true);

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
            io.swagger.v3.oas.annotations.headers.Header[] annotations,
            Components components) {

        Map<String, io.swagger.v3.oas.models.headers.Header> headers = new LinkedHashMap<>();

        for (io.swagger.v3.oas.annotations.headers.Header annotation : annotations) {
            Components local = new Components();
            Optional<io.swagger.v3.oas.models.headers.Header> parsed =
                    AnnotationsUtils.getHeader(annotation, local, null, true);

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
        if (canonical.getSchemas() != null)
            canonical.getSchemas().forEach(utilityComponents::addSchemas);

        return schema;
    }

    private void mergeReferenceParameter(Map<String, Parameter> parameters,
                                         io.swagger.v3.oas.annotations.Parameter annotation,
                                         WebMindRouteResolver.ResolvedRoute route,
                                         Components components) {
        if (annotation.hidden())
            return;

        Parameter referenced = referencedParameter(annotation.ref(), components, new LinkedHashSet<>());

        if (referenced == null) {
            if (!route.pathVariables().isEmpty())
                throw new IllegalArgumentException("Cannot verify an external parameter reference against path variables: " + annotation.ref());

            parameters.put("$ref:" + annotation.ref(), new Parameter().$ref(annotation.ref()));
            return;
        }

        if (referenced.getName() == null || referenced.getIn() == null) {
            throw new IllegalArgumentException("Referenced parameter requires name and in: " + annotation.ref());
        }

        if (PATH.equals(referenced.getIn())) {
            boolean nonMatch =
                    route.pathVariables().stream()
                            .noneMatch(variable -> variable.name().equals(referenced.getName()));
            if (nonMatch)
                throw new IllegalArgumentException("Referenced path parameter is absent from the route: " + referenced.getName());

            if (!Boolean.TRUE.equals(referenced.getRequired()))
                throw new IllegalArgumentException("Referenced path parameter must be required: " + annotation.ref());
        }

        parameters.put(key(referenced), new Parameter().$ref(annotation.ref()));
    }

    private Parameter referencedParameter(String ref, Components components, Set<String> visited) {
        String prefix = "#/components/parameters/";
        if (!ref.startsWith(prefix))
            return null;

        if (!visited.add(ref))
            throw new IllegalArgumentException("Circular parameter reference: " + ref);

        String name = ref.substring(prefix.length())
                .replace("~1", "/")
                .replace("~0", "~");

        Parameter parameter = components.getParameters() == null ? null : components.getParameters().get(name);
        if (parameter == null)
            throw new IllegalArgumentException("Unknown parameter reference: " + ref);

        return parameter.get$ref() == null ?
                parameter :
                referencedParameter(parameter.get$ref(), components, visited);
    }

    private Annotation[] parameterAnnotations(MappingMetadata mapping, int index) {
        Set<Class<?>> hierarchy = new LinkedHashSet<>(List.of(mapping.controllerClass()));
        hierarchy.addAll(ClassUtils.getAllInterfacesForClassAsSet(mapping.controllerClass()));

        for (Class<?> parent = mapping.controllerClass().getSuperclass(); parent != null; parent = parent.getSuperclass()) {
            hierarchy.add(parent);
        }

        Map<Class<? extends Annotation>, Annotation> annotations = new LinkedHashMap<>();
        for (Class<?> type : hierarchy) {
            Method[] candidates = type.getDeclaredMethods();
            for (Method candidate : candidates) {
                if (candidate.isBridge() || candidate.isSynthetic() || !sameMethod(candidate, mapping.method(), mapping.controllerClass()))
                    continue;

                Annotation[][] pms = candidate.getParameterAnnotations();
                if (pms.length == 0)
                    continue;

                for (Annotation annotation : pms[index]) {
                    annotations.putIfAbsent(annotation.annotationType(), annotation);
                }
            }
        }

        Annotation[][] methodAnns = mapping.method().getParameterAnnotations();
        if (methodAnns.length > 0) {
            for (Annotation annotation : methodAnns[index]) {
                annotations.putIfAbsent(annotation.annotationType(), annotation);
            }
        }

        return annotations.values().toArray(Annotation[]::new);
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
