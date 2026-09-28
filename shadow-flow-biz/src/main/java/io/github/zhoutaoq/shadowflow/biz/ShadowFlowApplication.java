package io.github.zhoutaoq.shadowflow.biz;

import io.github.zhoutaoq.shadowflow.biz.config.AppEnv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ShadowFlowApplication {

    public static void main(String[] args) {
        // 密钥装载必须在 Spring 起来之前：application.yml 的 ${XXX:} 占位符
        // 在创建 Environment 时就要求解析得出，晚了拿到的全是空串。
        // 读不到 app.env 直接终止启动（fail-fast），不允许密钥全空地静默跑起来。
        try {
            AppEnv.load();
        } catch (IllegalStateException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
        }
        SpringApplication.run(ShadowFlowApplication.class, args);
    }
}
