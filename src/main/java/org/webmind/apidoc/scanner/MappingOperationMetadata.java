package org.webmind.apidoc.scanner;

import io.swagger.v3.oas.models.Operation;

import java.lang.reflect.Method;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
public record MappingOperationMetadata(String beanName,
                                       Class<?> controllerClass,
                                       Method method,
                                       String[] pathArray,
                                       String[] httpMethods,
                                       boolean async,
                                       Operation operation) {
}
