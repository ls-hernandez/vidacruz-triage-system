package com.vidacruz.triage;

import org.springframework.boot.SpringApplication;

public class TestTriageServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(TriageServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
