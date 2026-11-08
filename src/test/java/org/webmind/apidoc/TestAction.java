package org.webmind.apidoc;

import lombok.extern.slf4j.Slf4j;
import org.mind.framework.annotation.Mapping;
import org.springframework.stereotype.Controller;

/**
 *
 * @author Marcus
 * @version 1.0
 * @date 2026/11/8
 */
@Controller
public class TestAction {

    @Mapping
    public String value(){
        System.out.println("This is test api-doc");
        return "api-doc";
    }
}
