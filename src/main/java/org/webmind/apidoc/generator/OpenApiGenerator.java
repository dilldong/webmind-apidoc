package org.webmind.apidoc.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;

import java.util.Objects;

/** 创建文档，序列化并缓存 JSON；启动流程由 WebMindMappingScanner 负责。 */
public class OpenApiGenerator {
    private final String definitionJson;
    private volatile String cachedJson;

    public OpenApiGenerator() {
        this(new OpenAPI());
    }

    public OpenApiGenerator(OpenAPI definition) {
        definitionJson = serialize(Objects.requireNonNull(definition, "definition"));
    }

    /** 每次使用独立文档，保留应用配置的 Info、servers 和共享 components。 */
    public OpenAPI createDocument() {
        OpenAPI document = deserialize(definitionJson);
        Info info = document.getInfo() == null ? new Info() : document.getInfo();
        if (info.getTitle() == null || info.getTitle().isBlank()) info.setTitle("WebMind API");
        if (info.getVersion() == null || info.getVersion().isBlank()) info.setVersion("1.0.0");
        document.setInfo(info);
        if (document.getPaths() == null) document.setPaths(new Paths());
        if (document.getComponents() == null) document.setComponents(new Components());
        return document;
    }

    /** 序列化成功后才替换缓存，失败时保留上一份 JSON。 */
    public String generate(OpenAPI document) {
        String json = serialize(document.specVersion(SpecVersion.V31).openapi("3.1.0"));
        cachedJson = json;
        return json;
    }

    public String getJson() {
        String json = cachedJson;
        if (json == null) throw new IllegalStateException("OpenAPI document has not been generated yet");
        return json;
    }

    /** 返回副本，避免调用方修改已缓存的文档。 */
    public OpenAPI getOpenAPI() {
        return deserialize(getJson());
    }

    private static String serialize(OpenAPI document) {
        try {
            return Json31.mapper().writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize OpenAPI 3.1 document", e);
        }
    }

    private static OpenAPI deserialize(String json) {
        try {
            return Json31.mapper().readValue(json, OpenAPI.class).specVersion(SpecVersion.V31).openapi("3.1.0");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read OpenAPI 3.1 document", e);
        }
    }
}
