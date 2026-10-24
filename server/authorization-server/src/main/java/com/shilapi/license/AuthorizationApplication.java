package com.shilapi.license;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LicenseProperties.class)
public class AuthorizationApplication {
    public static void main(String[] args) { SpringApplication.run(AuthorizationApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
