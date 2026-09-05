package com.stock.kline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class StockKlineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockKlineApplication.class, args);
    }
}
