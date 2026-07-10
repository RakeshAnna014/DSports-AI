package com.dsports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@SpringBootApplication
@EnableR2dbcRepositories(basePackages = "com.dsports")
public class DSportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DSportsApplication.class, args);
    }
}
