package com.rechang.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.rechang.api", "com.rechang.common"})
@MapperScan("com.rechang.api.mapper")
public class RechangApplication {
    public static void main(String[] args) {
        SpringApplication.run(RechangApplication.class, args);
    }
}
