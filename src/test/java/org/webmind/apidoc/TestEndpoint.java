package org.webmind.apidoc;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.mind.framework.annotation.Mapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Collections;
import java.util.List;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Controller
public class TestEndpoint {

    @Mapping
    @Hidden
    public String value() {
        System.out.println("This is test api-doc");
        return "api-doc";
    }

    @Mapping(value = {"/account1/${id}", "/account2/${id}"}, method = RequestMethod.GET)
    @Operation(operationId = "lookupAccount", summary = "Look up an account")
    public Account lookup2(long accountIdentifier) {
        return new Account();
    }

    @Mapping(value = {"/lookup/${id}"}, method = RequestMethod.GET)
    @Operation(summary = "Lookup")
    @Parameter(name = "id", in = ParameterIn.PATH, description = "Method fallback", schema = @Schema(minimum = "1"))
    public Payload lookup(@Parameter(name = "ignored", in = ParameterIn.HEADER, description = "Database identifier") long id) {
        return null;
    }

    @Mapping(value = {"/search"}, method = RequestMethod.GET)
    @Operation(parameters = @Parameter(name = "limit", in = ParameterIn.QUERY, description = "Operation value",
            schema = @Schema(type = "integer")))
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "Method takes precedence",
            schema = @Schema(type = "integer", maximum = "50"))
    @Parameter(name = "request-id", in = ParameterIn.HEADER, schema = @Schema(type = "string"))
    public List<Payload> search() {
        return Collections.emptyList();
    }

    @Mapping(value = {"/create"}, method = RequestMethod.POST)
    @Operation(requestBody = @RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Payload.class))))
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Payload.class)),
            headers = @Header(name = "Location", schema = @Schema(type = "string")))
    public void create() {
    }

    @Mapping(value = {"/delete"}, method = RequestMethod.DELETE)
    @ApiResponse(responseCode = "204", description = "Deleted")
    public Payload delete() {
        return null;
    }

    @Mapping(value = {"/payload"})
    @ApiResponse(responseCode = "202", useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public List<Payload> typedResponse() {
        return null;
    }

    @Mapping(value = {"/hiddenPath/${id}"})
    public Payload hiddenPath(@Parameter(hidden = true) long id) {
        return null;
    }

    @Mapping(value = {"/referencedPath/${id}"})
    @Parameter(ref = "#/components/parameters/Identifier")
    public Payload referencedPath(long id) {
        return null;
    }

    @Mapping(value = {"/collidingContent"})
    @Operation(requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = SecondCollision.class))))
    public void collidingContent() {
    }

    @Mapping(value = {"/collidingHeader"})
    @ApiResponse(responseCode = "200", headers = @Header(name = "Collision", schema = @Schema(implementation = SecondCollision.class)))
    public void collidingHeader() {
    }

    @Mapping(value = {"/collidingContentBatch"})
    @ApiResponse(responseCode = "200", content = {
            @Content(mediaType = "application/json", schema = @Schema(implementation = FirstCollision.class)),
            @Content(mediaType = "application/xml", schema = @Schema(implementation = SecondCollision.class))})
    public void collidingContentBatch() {
    }

    @Mapping(value = {"/contextualSchemas"}, method = RequestMethod.POST)
    @Operation(requestBody = @RequestBody(content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = Payload.class, description = "Incoming payload"),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(name = "sample", value = "{\"name\":\"sample\"}"),
            encoding = @io.swagger.v3.oas.annotations.media.Encoding(name = "name", contentType = "text/plain"))))
    @ApiResponse(responseCode = "201", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = Payload.class, description = "Outgoing payload")),
            headers = @Header(name = "X-Payload", schema = @Schema(implementation = Payload.class, description = "Header payload")))
    public Payload contextualSchemas(/*@RequestBody Payload payload*/) {
        return null;
    }

    public static class Payload {
        @Schema(description = "A display name")
        public String name;
    }

    @Schema(name = "Collision")
    public static class FirstCollision {
        public String first;
    }

    @Schema(name = "Collision")
    public static class SecondCollision {
        public String second;
    }

    public static class Account {
        public String name;
    }

}
