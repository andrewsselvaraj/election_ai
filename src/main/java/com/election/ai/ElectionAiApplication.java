package com.election.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ElectionAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElectionAiApplication.class, args);
    }
}
