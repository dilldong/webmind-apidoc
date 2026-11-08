package org.webmind.apidoc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.webmind.apidoc.scanner.WebMindMappingScanner;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Slf4j
@Configuration
@PropertySource("classpath:frame.properties")
@ComponentScan(basePackages = {"org.webmind.apidoc"})
@RequiredArgsConstructor
public class AppConfiguration {

    @Bean
    public WebMindMappingScanner webmindMappingScanner(ApplicationContext applicationContext) {
        return new WebMindMappingScanner(applicationContext);
    }
}
