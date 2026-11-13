package com.vidacruz.triage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "triage.escalation.enabled=false")
class TriageServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
