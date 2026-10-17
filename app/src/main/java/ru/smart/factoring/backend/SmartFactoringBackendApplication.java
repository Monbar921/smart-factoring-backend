package ru.smart.factoring.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class SmartFactoringBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartFactoringBackendApplication.class, args);
    }
}
