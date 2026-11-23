package org.webmind.apidoc.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/**
 * 创建文档，序列化并缓存 JSON；启动流程由 WebMindMappingScanner 负责。
 * @author marcus
 * @version 1.0
 * @date 2026/11/8
 */
public class OpenApiGenerator {
    private static final String API_VERSION = "3.1.0";
    private final String definitionJson;
    private volatile String cachedJson;

    public OpenApiGenerator() {
        this(new OpenAPI());
    }

    public OpenApiGenerator(OpenAPI definition) {
        definitionJson = serialize(Objects.requireNonNull(definition, "definition"));
    }

    /**
     * 每次使用独立文档，保留应用配置的 Info、servers 和共享 components。
     */
    public OpenAPI createDocument() {
        OpenAPI document = deserialize(definitionJson);
        Info info = document.getInfo() == null ? new Info() : document.getInfo();

        if (StringUtils.isBlank(info.getTitle()))
            info.setTitle("API");

        if (StringUtils.isBlank(info.getVersion()))
            info.setVersion("1.0.0");

        document.setInfo(info);

        if (document.getPaths() == null)
            document.setPaths(new Paths());

        // 全局 OpenAPI 组件库（用于收纳复用的 Schema、Responses 等，支持 $ref 引用）
        if (document.getComponents() == null)
            document.setComponents(new Components());

        return document;
    }

    /**
     * 序列化成功后才替换缓存，失败时保留上一份 JSON。
     */
    public String generate(OpenAPI document) {
        String json = serialize(document.specVersion(SpecVersion.V31).openapi(API_VERSION));
        cachedJson = json;
        return json;
    }

    public String getJson() {
        String json = cachedJson;
        if (StringUtils.isEmpty(json))
            throw new IllegalStateException("OpenAPI document has not been generated yet");

        return json;
    }

    /**
     * 返回副本，避免调用方修改已缓存的文档。
     */
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
            return Json31.mapper()
                    .readValue(json, OpenAPI.class)
                    .specVersion(SpecVersion.V31)
                    .openapi(API_VERSION);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read OpenAPI 3.1 document", e);
        }
    }
}
