package com.stock.kline.watch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DingTalkNotifierTest {

    @Autowired
    private DingTalkNotifier notifier;

    @Test
    void send() {
        notifier.send("单元测试推送，现价29");
    }
}