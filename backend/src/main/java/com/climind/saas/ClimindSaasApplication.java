package com.climind.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClimindSaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClimindSaasApplication.class, args);
    }
}
