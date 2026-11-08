package org.webmind.apidoc.scanner;

import lombok.extern.slf4j.Slf4j;
import org.mind.framework.annotation.Mapping;
import org.mind.framework.util.ReflectionUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 负责获取 Controller Bean，并提取所有带有 @Mapping 的方法。
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Slf4j
public class WebMindMappingScanner implements SmartInitializingSingleton {
    private final ApplicationContext applicationContext;
    private final List<MappingMetadata> mappings;

    public WebMindMappingScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.mappings = new ArrayList<>();
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 获取所有 Controller Bean
        Map<String, Object> controllers = applicationContext.getBeansWithAnnotation(Controller.class);

        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            String beanName = entry.getKey();

            log.debug("Webmind APIDoc: {}", beanName);
            scanController(beanName, entry.getValue());
        }
    }

    private void scanController(String beanName, Object bean) {
        // 处理 Spring AOP 代理
        Class<?> controllerClass = AopProxyUtils.ultimateTargetClass(bean);

        // 遍历 Controller 的方法，包括继承的方法
        ReflectionUtils.doWithMethods(
                controllerClass,
                method -> scanMethod(
                        beanName,
                        controllerClass,
                        method
                ),
                method -> !method.isBridge() && !method.isSynthetic()
        );
    }

    private void scanMethod(String beanName, Class<?> controllerClass, Method method) {
        // 查找 WebMind @Mapping
        Mapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, Mapping.class);

        if (mapping == null)
            return;

        String[] methods = Arrays.stream(mapping.method())
                .map(Enum::name)
                .toArray(String[]::new);

        MappingMetadata metadata = new MappingMetadata(
                beanName,
                controllerClass,
                method,
                mapping.value(),
                methods,
                mapping.async().value()
        );

        mappings.add(metadata);
    }

    public List<MappingMetadata> getMappings() {
        return Collections.unmodifiableList(mappings);
    }
}
