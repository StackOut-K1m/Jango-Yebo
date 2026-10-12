package com.jangoyebo;

import org.springframework.boot.SpringApplication;

public class TestJangoApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(JangoApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
