package org.webmind.apidoc.endpoint;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mind.framework.annotation.Mapping;
import org.mind.framework.util.JsonUtils;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.webmind.apidoc.OpenApiEnabledCondition;
import org.webmind.apidoc.generator.OpenApiGenerator;

/**
 *
 * @author: Marcus
 * @date: 2026/11/10
 * @version: 1.0
 */
@Slf4j
@Controller
@Conditional(OpenApiEnabledCondition.class)
@RequiredArgsConstructor
public class OpenApiEndpoint {
    private final OpenApiGenerator openApiGenerator;

    @Mapping(value = "/v3/api-docs", method = RequestMethod.GET, requestLog = false)
    @Hidden
    public String apiDocs() {
        try {
//            return FileUtils.read("/Users/marcus/Downloads/duova-openapi.json");
            return openApiGenerator.getJson();
        } catch (IllegalStateException e) {
            log.warn("[OpenAPI] {} - {}", e.getMessage(), "/v3/api-docs");
            return JsonUtils.EMPTY_JSON_OBJECT;
        }
    }
}
