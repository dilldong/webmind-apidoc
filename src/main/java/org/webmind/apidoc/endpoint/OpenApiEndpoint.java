package org.webmind.apidoc.endpoint;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.mind.framework.annotation.Mapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.webmind.apidoc.generator.OpenApiGenerator;

/**
 *
 * @author: Marcus
 * @date: 2026/11/10
 * @version: 1.0
 */
@Controller
@RequiredArgsConstructor
public class OpenApiEndpoint {
    private final OpenApiGenerator openApiGenerator;

    @Mapping(value = "/v3/api-docs", method = RequestMethod.GET, requestLog = false)
    @Hidden
    public String apiDocs() {
        return openApiGenerator.getJson();
    }
}
