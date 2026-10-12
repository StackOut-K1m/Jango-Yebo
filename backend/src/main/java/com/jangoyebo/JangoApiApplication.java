package com.jangoyebo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JangoApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(JangoApiApplication.class, args);
	}

}
