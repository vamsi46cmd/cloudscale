package io.cloudscale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaRepositories(considerNestedRepositories = true)
public class CloudScaleApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudScaleApplication.class, args);
    }
}
