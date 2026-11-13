# webmind-apidoc
Add OpenAPI Adapter support for WebMind, using Swagger Core under the hood.

## 使用

要求 Java 17。当前适配 WebMind 5.1.4，使用 Swagger Core Jakarta 2.2.55。

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

`OpenApiAnnotationParser.parse()` 完成每个接口的注解、参数和响应解析。
`OpenApiGenerator` 只提供 `createDocument()` 和 `generate(document)`，不参与 Spring 生命周期。
业务代码注入生成器后，调用 `getJson()` 读取缓存；`getOpenAPI()` 返回独立的模型副本。
生成失败时保留上一次成功的缓存。启动扫描完成之前调用 `getJson()` 会抛出尚未就绪异常。

本模块实现扫描、路由解析、注解解析、Schema 解析和文档生成；HTTP 文档端点及 Swagger UI 由应用接入。

## 注解示例

```java
@Controller
public class UserAction {
    @Mapping(value = "/users/${id}", method = RequestMethod.GET)
    @Operation(summary = "查询用户", operationId = "getUser",
        parameters = @Parameter(name = "verbose", in = ParameterIn.QUERY,
            description = "是否返回详细信息", schema = @Schema(type = "boolean")))
    @ApiResponse(responseCode = "200", description = "用户信息",
        content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = User.class)))
    public User get(@Parameter(description = "用户 ID") long id) {
        // 业务逻辑；通过 Action 读取 query/header/body。
        return new User();
    }
}

@Schema(description = "用户")
public class User {
    @Schema(description = "用户名称", requiredMode = Schema.RequiredMode.REQUIRED)
    public String name;
}
```

上例中 Swagger 注解来自 `io.swagger.v3.oas.annotations` 及其 `enums`、`media`、`responses` 子包；
`Mapping` 来自 WebMind，`Controller` 和 `RequestMethod` 来自 Spring。

## 各层职责

| 组件 | 职责 |
| --- | --- |
| `WebMindMappingScanner` | 统一编排扫描、解析、Operation 组装及 JSON 缓存 |
| `WebMindRouteResolver` | 路径与 HTTP 方法展开、`${id}` 转 `{id}`、按 URI 顺序关联方法参数、路由冲突检查 |
| `OpenApiAnnotationParser` | 注解转换及路径参数、默认响应推断 |
| `OpenApiSchemaResolver` | 原生 3.1 Java Type 转 Schema，并收集当前文档的引用模型 |
| `OpenApiGenerator` | 创建文档、序列化及缓存 JSON |

`@Mapping` 决定实际路径与 HTTP 方法，Swagger 注解不修改运行时路由。
路径保持应用内的原始层级和斜杠，不添加 Spring MVC 前缀或部署 context path。
每个路径与 HTTP 方法组合生成独立的 operation；默认 `@Mapping` 为 `/` 的 GET 和 POST。
当一个 Java 方法展开为多个 operation 时，重复的 operationId 依次添加 `_2`、`_3` 等后缀。

路径变量名称来自 URI，类型按 URI 中的变量顺序与 Java 方法参数关联，始终为必填。
通过 `Action` 在方法体内读取的 query、header 和 JSON body 无法由反射推断，需使用显式文档注解。
支持 `@Operation`、`@Parameter`/`@Parameters`、`@Schema`、`@ApiResponse`/`@ApiResponses`、
`@Hidden` 和 `@Tag`。请求体可以通过 `@Operation(requestBody = ...)` 描述。

Schema 解析保留泛型，支持 DTO、枚举、数组、集合、Map 和递归模型。
未显式描述响应时，普通 DTO 推断为 JSON，字符串和基本类型按 WebMind 的默认文本渲染描述；
`void`、`Void` 或 `Render` 不推断响应体。显式响应注解优先。
存在显式响应注解时不额外添加默认 `200`；需要为显式响应推断返回模型时，设置
`@ApiResponse(useReturnTypeSchema = true)`。
模型字段使用 Swagger Core 的解析规则；应用自定义 Gson 序列化规则时，应显式核对文档与实际输出。

无法准确表达为 OpenAPI 的通配符路由、无效路径模板、参数数量不匹配，以及路由/模型名称冲突，
会抛出包含来源信息的异常。隐藏接口在解析路由之前排除。
可复用的路径参数引用需在 `components.parameters` 中提供本地定义；带路径变量的路由会拒绝
无法核对名称与必填标记的外部参数引用。

## 自定义文档信息

可在应用中提供一个 `OpenAPI` Bean，设置元信息、servers、安全方案和共享 components。
生成器在创建时保存其快照，每次生成使用独立模型，不会修改应用提供的 Bean。

```java
@Bean
public OpenAPI openApiDefinition() {
    return new OpenAPI(SpecVersion.V31)
        .openapi("3.1.0")
        .info(new Info().title("用户服务").version("1.0.0"));
}
```

## 验证

```shell
mvn test
```
