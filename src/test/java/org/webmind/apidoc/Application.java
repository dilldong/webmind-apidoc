package org.webmind.apidoc;

import org.mind.framework.SpringApplication;
import org.springframework.context.annotation.Import;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Import(AppConfiguration.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
