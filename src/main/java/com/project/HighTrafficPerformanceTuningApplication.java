package com.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class HighTrafficPerformanceTuningApplication {

	public static void main(String[] args) {
		SpringApplication.run(HighTrafficPerformanceTuningApplication.class, args);
	}

}
