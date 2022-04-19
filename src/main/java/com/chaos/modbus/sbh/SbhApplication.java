package com.chaos.modbus.sbh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SbhApplication {

    public static void main(String[] args) {
        SpringApplication.run(SbhApplication.class, args);
    }

}
