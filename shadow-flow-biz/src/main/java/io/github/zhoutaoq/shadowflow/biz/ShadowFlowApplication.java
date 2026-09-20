package io.github.zhoutaoq.shadowflow.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ShadowFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShadowFlowApplication.class, args);
    }
}
