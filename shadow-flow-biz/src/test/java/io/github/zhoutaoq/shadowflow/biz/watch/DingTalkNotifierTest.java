package io.github.zhoutaoq.shadowflow.biz.watch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootTest
class DingTalkNotifierTest {

    @Autowired
    private DingTalkNotifier notifier;

    @Test
    void send() throws UnknownHostException {
        notifier.send(String.format("ip:" + InetAddress.getLocalHost()));
    }
}