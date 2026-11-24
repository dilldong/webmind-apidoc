package org.webmind.apidoc.endpoint;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.mind.framework.annotation.Mapping;
import org.mind.framework.util.ViewResolver;
import org.mind.framework.web.Action;
import org.mind.framework.web.renderer.Render;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.webmind.apidoc.OpenApiEnabledCondition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @author marcus
 * @version 1.0
 * @date 2026/11/24
 */
@Hidden
@Controller
@Conditional(OpenApiEnabledCondition.class)
public class OpenApiUiEndpoint {
    private static final String UI_DIRECTORY = "org/webmind/apidoc/ui/";
    private static final String JAVASCRIPT = "text/javascript";

    private static final Map<String, Asset> ASSETS = Map.of(
            "styles", new Asset("console.css", "text/css"),
            "jquery", new Asset("jquery.min.js", JAVASCRIPT),
            "openapi", new Asset("openapi.js", JAVASCRIPT),
            "history", new Asset("history.js", JAVASCRIPT),
            "console", new Asset("console.js", JAVASCRIPT)
    );

    @Mapping(value = "/api-docs", method = RequestMethod.GET, requestLog = false)
    public Render page() {
        return assetResponse(new Asset("index.html", MediaType.TEXT_HTML_VALUE));
    }

    @Mapping(value = "/api-docs/assets/${name}", method = RequestMethod.GET, requestLog = false)
    public Render asset(String name) {
        Asset asset = ASSETS.get(name);
        if (asset == null) {
            Action.getActionContext().getResponse().setStatus(HttpServletResponse.SC_NOT_FOUND);
            return ViewResolver.text(HttpStatus.NOT_FOUND.getReasonPhrase());
        }

        return assetResponse(asset);
    }

    private Render assetResponse(Asset asset) {
        try {
            String content = new ClassPathResource(
                    UI_DIRECTORY + asset.fileName(), OpenApiUiEndpoint.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);

            Render render = ViewResolver.text(content);
            render.setContentType(asset.contentType());
            return render;

        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load API console asset: " + asset.fileName(), e);
        }
    }

    private record Asset(String fileName, String contentType) {
    }
}
