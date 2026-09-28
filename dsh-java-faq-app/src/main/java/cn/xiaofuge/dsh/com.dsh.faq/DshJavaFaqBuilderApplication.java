package cn.xiaofuge.dsh.com.dsh.faq;

import cn.xiaofuge.dsh.com.dsh.faq.config.FaqBuilderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FaqBuilderProperties.class)
public class DshJavaFaqBuilderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaFaqBuilderApplication.class, args);
    }
}
