package org.webmind.apidoc.resolver;

import io.swagger.v3.oas.models.PathItem;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.mind.framework.util.MatcherUtils;
import org.webmind.apidoc.scanner.MappingMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 WebMind 路由展开为 OpenAPI 路径和 HTTP Method。
 *
 * <p>路径相对于应用 context path，不添加类名、方法名或 Spring MVC 前缀。
 * WebMind 按 URI 中 {@code ${name}} 的出现顺序绑定方法参数，变量名不依赖
 * Java 参数名或 {@code -parameters} 编译选项。</p>
 *
 * @author Marcus
 */
public class WebMindRouteResolver {
    private static final Pattern VARIABLE_NAME = Pattern.compile("[\\p{L}\\p{N}_.-]+");

    /**
     * 展开所有映射，校验全局路由冲突，并提供稳定的生成顺序。
     */
    public List<ResolvedRoute> resolve(List<MappingMetadata> mappings) {
        return mappings.stream()
                .flatMap(mapping -> resolve(mapping).stream())
                .sorted(Comparator.comparing(ResolvedRoute::path)
                        .thenComparing(route -> route.httpMethod().name())
                        .thenComparing(route -> route.metadata().beanName())
                        .thenComparing(route -> route.metadata().method().toGenericString()))
                .toList();
    }

    /**
     * 每个路径和 HTTP Method 组合产生一个路由。重复的组合只保留第一次。
     *
     * @throws IllegalArgumentException 路由无法表达为 OpenAPI 路径，或与方法参数不匹配
     */
    public List<ResolvedRoute> resolve(MappingMetadata mapping) {
        Objects.requireNonNull(mapping, "metadata must not be null");
        Objects.requireNonNull(mapping.method(), "metadata.method must not be null");

        if (ArrayUtils.isEmpty(mapping.originalPath()))
            throw invalid(mapping, null, "at least one path is required");

        if (ArrayUtils.isEmpty(mapping.httpMethods()))
            throw invalid(mapping, null, "at least one HTTP method is required");

        Set<ResolvedRoute> routes = new LinkedHashSet<>();
        for (String originalPath : mapping.originalPath()) {
            ParsedPath parsed = parsePath(mapping, originalPath);
            for (String method : mapping.httpMethods()) {
                PathItem.HttpMethod httpMethod = parseHttpMethod(mapping, originalPath, method);
                routes.add(new ResolvedRoute(mapping, parsed.path(), httpMethod, parsed.variables()));
            }
        }
        return List.copyOf(routes);
    }

    private ParsedPath parsePath(MappingMetadata mapping, String originalPath) {
        if (originalPath == null || originalPath.isBlank() || !originalPath.startsWith("/")) {
            throw invalid(mapping, originalPath, "path must start with '/' and must not be blank");
        }
        if (originalPath.chars().anyMatch(Character::isWhitespace)
                || originalPath.indexOf('?') >= 0 || originalPath.indexOf('#') >= 0) {
            throw invalid(mapping, originalPath, "path must not contain whitespace, a query string or a fragment");
        }
        if (originalPath.indexOf('*') >= 0) {
            throw invalid(mapping, originalPath, "wildcard routes cannot be represented as an OpenAPI path");
        }

        Matcher matcher = MatcherUtils.URI_PARAM_PATTERN.matcher(originalPath);
        StringBuilder path = new StringBuilder();
        List<PathVariable> variables = new ArrayList<>();
        Set<String> names = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group().substring(2, matcher.group().length() - 1);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw invalid(mapping, originalPath, "invalid path variable name: " + name);
            }
            if (!names.add(name)) {
                throw invalid(mapping, originalPath, "duplicate path variable name: " + name);
            }
            variables.add(new PathVariable(name, variables.size()));
            matcher.appendReplacement(path, Matcher.quoteReplacement("{" + name + "}"));
        }
        matcher.appendTail(path);

        // Only WebMind ${...} placeholders may become OpenAPI {...} templates.
        String literalPath = MatcherUtils.URI_PARAM_PATTERN.matcher(originalPath).replaceAll("");
        if (literalPath.indexOf('{') >= 0 || literalPath.indexOf('}') >= 0) {
            throw invalid(mapping, originalPath, "unmatched braces or a non-WebMind path template");
        }
        if (variables.size() != mapping.method().getParameterCount()) {
            throw invalid(mapping, originalPath, "path variable count (" + variables.size()
                    + ") does not match method parameter count (" + mapping.method().getParameterCount() + ")");
        }
        return new ParsedPath(path.toString(), List.copyOf(variables));
    }

    private PathItem.HttpMethod parseHttpMethod(MappingMetadata mapping, String path, String method) {
        if (StringUtils.isNotEmpty(method)) {
            try {
                return PathItem.HttpMethod.valueOf(method);
            } catch (IllegalArgumentException ignored) {
                // Include the originating Java method in the actionable error below.
            }
        }
        throw invalid(mapping, path, "unsupported OpenAPI HTTP method: " + method);
    }

    private IllegalArgumentException invalid(MappingMetadata mapping, String path, String reason) {
        return new IllegalArgumentException("Invalid WebMind route '" + path + "' on "
                + mapping.method().toGenericString() + ": " + reason);
    }

    /**
     * One operation in an application-relative OpenAPI path.
     */
    public record ResolvedRoute(MappingMetadata metadata,
                                String path,
                                PathItem.HttpMethod httpMethod,
                                List<PathVariable> pathVariables) {
    }

    /**
     * The zero-based method parameter index follows placeholder order in the URI.
     */
    public record PathVariable(String name, int parameterIndex) {
    }

    private record ParsedPath(String path, List<PathVariable> variables) {
    }
}
