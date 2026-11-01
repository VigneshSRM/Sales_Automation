package com.infeara.sales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Packaged as a WAR and deployed to an external Apache Tomcat 11
 * (Jakarta Servlet 6.1) as required by the deployment architecture (ADR-001).
 */
@SpringBootApplication
@EnableScheduling
public class SalesApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(SalesApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(SalesApplication.class, args);
    }
}
