package br.com.deroyque.activation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.ActivationRequestRepository;
import br.com.deroyque.activation.domain.RequestStatus;
import br.com.deroyque.activation.support.TestProperties;

/** Reacao do processador a cada resultado do provedor, com o provedor e o banco simulados. */
class ActivationProcessorTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
	private static final Duration BACKOFF = Duration.ofSeconds(10);

	private final ActivationRequestRepository repository = mock(ActivationRequestRepository.class);
	private final ActivationProvider provider = mock(ActivationProvider.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private ActivationProcessor processor;
	private ActivationRequest request;
	private ProviderRequest sent;

	@BeforeEach
	void setUp() {
		TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
		processor = new ActivationProcessor(repository, tx, provider, attempt -> BACKOFF, events,
				Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.defaults());

		request = ActivationRequest.create("owner", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", NOW.minusSeconds(60));
		when(repository.lockDueForProcessing(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(request));
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));
		sent = processor.claimDue(10).get(0);
	}

	@Test
	void claimMarksRequestAsProcessingAndCountsTheAttempt() {
		assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
		assertThat(sent.attempt()).isEqualTo(1);
		assertThat(sent.requestId()).isEqualTo(request.getId());
		assertThat(sent.correlationId()).isEqualTo("corr-1");
	}

	@Test
	void successCompletesAndPublishesEvent() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.Activated());

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
		ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue()).isEqualTo(
				new ActivationFinished(request.getId(), "EXT-1", RequestStatus.COMPLETED, null));
	}

	@Test
	void rejectionFailsWithoutRetry() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.Rejected("HTTP 400"));

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
		assertThat(request.getFailureReason()).isEqualTo("HTTP 400");
	}

	@Test
	void transientFailureReturnsToPendingWithBackoff() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.RetryLater("HTTP 500", null));

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(request.getAttempts()).isEqualTo(1);
		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plus(BACKOFF));
		verifyNoInteractions(events);
	}

	@Test
	void retryAfterLongerThanBackoffIsRespected() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.RetryLater("HTTP 429", Duration.ofSeconds(60)));

		processor.process(sent);

		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
	}

	@Test
	void retryAfterIsCappedByMaxDelay() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.RetryLater("HTTP 429", Duration.ofDays(1)));

		processor.process(sent);

		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plus(TestProperties.defaults().retry().maxDelay()));
	}

	@Test
	void failsWhenAttemptsAreExhausted() {
		// maxAttempts = 3: leva a solicitacao ate a terceira tentativa
		request.scheduleRetry("falha", NOW, NOW);
		request.startProcessing(NOW);
		request.scheduleRetry("falha", NOW, NOW);
		request.startProcessing(NOW);
		ProviderRequest third = ProviderRequest.from(request);
		assertThat(third.attempt()).isEqualTo(3);
		when(provider.activate(third)).thenReturn(new ProviderOutcome.RetryLater("Timeout", null));

		processor.process(third);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
		assertThat(request.getFailureReason()).contains("Tentativas esgotadas").contains("Timeout");
	}

	@Test
	void unavailableProviderDoesNotConsumeAnAttempt() {
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.Unavailable("circuito aberto", Duration.ofSeconds(30)));

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(request.getAttempts()).isZero();
		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void ignoresResultWhenRequestChangedMeanwhile() {
		request.complete(NOW); // outra instancia ja concluiu
		when(provider.activate(sent)).thenReturn(new ProviderOutcome.Rejected("HTTP 400"));

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
	}

	@Test
	void unexpectedErrorDoesNotPropagateAndLeavesRequestForReconciliation() {
		when(provider.activate(sent)).thenThrow(new IllegalStateException("bug"));

		processor.process(sent);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
	}
}
