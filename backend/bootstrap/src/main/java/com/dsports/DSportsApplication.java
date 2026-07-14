package com.dsports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.dsports")
@EnableR2dbcRepositories(basePackages = "com.dsports")
@EnableScheduling
public class DSportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DSportsApplication.class, args);
    }

}
