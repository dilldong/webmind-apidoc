package org.webmind.apidoc;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 启动开关，缺省为 true。生产环境设为 false 后，适配器会跳过文档扫描和生成，并移除 `/v3/api-docs` 的路由
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
public final class OpenApiEnabledCondition implements Condition {
    private static final String ENABLED_PROPERTY = "webmind.apidoc.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty(ENABLED_PROPERTY, Boolean.class, true);
    }
}
