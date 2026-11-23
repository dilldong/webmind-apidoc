# webmind-apidoc
Add OpenAPI Adapter support for WebMind, using Swagger Core under the hood.

## 使用

要求 Java 17+。使用 Swagger Core Jakarta 2.2.55。

在应用配置上导入适配器：

```java
@Import(WebMindOpenApiConfiguration.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

启动流程统一放在 `WebMindMappingScanner.afterSingletonsInstantiated()`：

```text
扫描 Controller / @Mapping
    → 解析路径和 HTTP 方法
    → 读取 Swagger 注解、解析参数和返回值
    → 生成 Operation 并加入 Paths
    → 生成并缓存 OpenAPI 3.1 JSON
```

本模块实现扫描、路由解析、注解解析、Schema 解析和文档生成；HTTP 文档端点及 Swagger UI 由应用接入。

## 注解示例

```java
@Controller
public class UserController {
    @Mapping(value = "/users/${id}", method = RequestMethod.GET)
    @Operation(summary = "查询用户", operationId = "getUser",
        parameters = @Parameter(name = "verbose", in = ParameterIn.QUERY,
            description = "是否返回详细信息", schema = @Schema(type = "boolean")))
    @ApiResponse(responseCode = "200", description = "用户信息",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = User.class)))
    public User get(@Parameter(description = "用户 ID") long id) {
        // 业务逻辑；通过 Controller 读取 query/header/body。
        return new User();
    }
}

@Schema(description = "用户")
public class User {
    @Schema(description = "用户名称", requiredMode = Schema.RequiredMode.REQUIRED)
    public String name;
}
```

上例中 Swagger 注解来自 `io.swagger.v3.oas.annotations` 及其 `enums`、`media`、`responses` 子包。

## 组件职责

| 组件 | 职责 |
| --- | --- |
| `WebMindMappingScanner` | 统一编排扫描、解析、Operation 组装及 JSON 缓存 |
| `WebMindRouteResolver` | 路径与 HTTP 方法展开、`${id}` 转 `{id}`、按 URI 顺序关联方法参数、路由冲突检查 |
| `OpenApiAnnotationParser` | 注解转换及路径参数、默认响应推断 |
| `OpenApiSchemaResolver` | 原生 3.1 Java Type 转 Schema，并收集当前文档的引用模型 |
| `OpenApiGenerator` | 创建文档、序列化及缓存 JSON |

支持 `@Operation`、`@Parameter`/`@Parameters`、`@Schema`、`@ApiResponse`/`@ApiResponses`、
`@Hidden` 和 `@Tag`。请求体可以通过 `@Operation(requestBody = ...)` 描述。

Schema 解析保留泛型，支持 DTO、枚举、数组、集合、Map 和递归模型。
未显式描述响应时，普通 DTO 推断为 JSON，字符串和基本类型按 WebMind 的默认文本渲染描述；
`void`、`Void` 或 `Render` 不推断响应体。显式响应注解优先。

存在显式响应注解时不额外添加默认 `200`；需要为显式响应推断返回模型时，设置
`@ApiResponse(useReturnTypeSchema = true)`。
