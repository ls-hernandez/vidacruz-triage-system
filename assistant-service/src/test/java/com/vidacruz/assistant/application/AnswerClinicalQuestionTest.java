package com.vidacruz.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.application.port.AnswerGenerator;
import com.vidacruz.assistant.application.port.ProtocolRetriever;
import com.vidacruz.assistant.domain.ClinicalAnswer;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import com.vidacruz.assistant.domain.Source;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** RN-05 rules of the use case, with fakes of both ports. */
class AnswerClinicalQuestionTest {

	private static final ProtocolFragment MEASURES = ProtocolFragment.of("PROTO-001",
			"Crisis Hipertensiva, manejo inicial", "Medidas iniciales", "Reconfirmar la presión tras 5 minutos de reposo.");
	private static final ProtocolFragment DEFINITION = ProtocolFragment.of("PROTO-001",
			"Crisis Hipertensiva, manejo inicial", "Definición", "Presión arterial ≥ 180/120 mmHg.");
	private static final ProtocolFragment REHYDRATION = ProtocolFragment.of("PROTO-003", "Deshidratación pediátrica",
			"Rehidratación", "Solución oral 50 ml/kg en 4 horas.");
	private static final Corpus CORPUS = new Corpus(List.of(MEASURES, DEFINITION, REHYDRATION));

	private final AtomicInteger generations = new AtomicInteger();

	@Test
	void nothingRetrievedGivesNoProtocolWithoutCallingTheModel() {
		verify(List.of(), new GeneratedAnswer("irrelevante", List.of(MEASURES.id())), ClinicalAnswer.noProtocol());

		assertThat(generations).hasValue(0);
	}

	@Test
	void citedFragmentsBecomeSourcesInCitationOrderWithoutDuplicates() {
		GeneratedAnswer generated = new GeneratedAnswer("  Reposo de 5 minutos.  ",
				List.of(DEFINITION.id(), MEASURES.id(), DEFINITION.id()));

		verify(List.of(MEASURES, DEFINITION), generated, new ClinicalAnswer("Reposo de 5 minutos.",
				List.of(new Source("PROTO-001", "Definición"), new Source("PROTO-001", "Medidas iniciales"))));
	}

	@Test
	void noProtocolMarkAnywhereInTheTextIsRejected() {
		verify(List.of(MEASURES), new GeneratedAnswer("Lo siento: SIN_PROTOCOLO", List.of(MEASURES.id())),
				ClinicalAnswer.noProtocol());
	}

	@Test
	void blankOrNullTextIsRejected() {
		verify(List.of(MEASURES), new GeneratedAnswer("  ", List.of(MEASURES.id())), ClinicalAnswer.noProtocol());
		verify(List.of(MEASURES), new GeneratedAnswer(null, List.of(MEASURES.id())), ClinicalAnswer.noProtocol());
	}

	@Test
	void noCitationIsRejected() {
		verify(List.of(MEASURES), new GeneratedAnswer("Reposo.", List.of()), ClinicalAnswer.noProtocol());
		verify(List.of(MEASURES), new GeneratedAnswer("Reposo.", null), ClinicalAnswer.noProtocol());
	}

	@Test
	void oneUnknownIdRejectsTheWholeAnswer() {
		verify(List.of(MEASURES), new GeneratedAnswer("Reposo.", List.of(MEASURES.id(), "PROTO-001#inventada")),
				ClinicalAnswer.noProtocol());
	}

	@Test
	void corpusFragmentThatWasNotRetrievedIsRejected() {
		verify(List.of(MEASURES), new GeneratedAnswer("Solución oral.", List.of(REHYDRATION.id())),
				ClinicalAnswer.noProtocol());
	}

	@Test
	void retrievedFragmentThatIsNotInTheCorpusIsRejected() {
		ProtocolFragment foreign = ProtocolFragment.of("PROTO-999", "Externo", "Sección", "Texto ajeno al corpus.");

		verify(List.of(foreign), new GeneratedAnswer("Texto ajeno.", List.of(foreign.id())),
				ClinicalAnswer.noProtocol());
	}

	@Test
	void providerErrorsPropagate() {
		AnswerClinicalQuestion useCase = new AnswerClinicalQuestion(question -> Mono.just(List.of(MEASURES)),
				(question, context) -> Mono.error(new AssistantUnavailableException("down", null)), CORPUS);

		StepVerifier.create(useCase.answer("pregunta")).verifyError(AssistantUnavailableException.class);
	}

	private void verify(List<ProtocolFragment> retrieved, GeneratedAnswer generated, ClinicalAnswer expected) {
		ProtocolRetriever retriever = question -> Mono.just(retrieved);
		AnswerGenerator generator = (question, context) -> {
			generations.incrementAndGet();
			assertThat(context).isEqualTo(retrieved);
			return Mono.just(generated);
		};
		StepVerifier.create(new AnswerClinicalQuestion(retriever, generator, CORPUS).answer("pregunta"))
			.expectNext(expected)
			.verifyComplete();
	}

}
