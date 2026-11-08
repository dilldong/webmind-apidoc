package org.webmind.apidoc.scanner;

import java.lang.reflect.Method;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
public record MappingMetadata(String beanName,
                              Class<?> controllerClass,
                              Method method,
                              String[] pathArray,
                              String[] httpMethods,
                              boolean async) {
}
