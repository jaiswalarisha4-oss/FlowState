package com.flowstate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for FlowState — a personal finance tracking and
 * recommendation assistant.
 *
 * On startup with an empty database, {@link com.flowstate.service.DemoDataSeeder}
 * generates a realistic synthetic transaction history so the app is fully
 * demoable without any external bank connection.
 */
@SpringBootApplication
public class FlowStateApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowStateApplication.class, args);
    }
}
