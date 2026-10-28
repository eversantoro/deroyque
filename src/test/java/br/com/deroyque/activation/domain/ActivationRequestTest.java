package br.com.deroyque.activation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Regras de dominio: sem Spring, sem banco. */
class ActivationRequestTest {

	private static final Instant T0 = Instant.parse("2026-10-26T14:00:00Z");
	private static final Instant T1 = T0.plusSeconds(10);

	private ActivationRequest newRequest() {
		return ActivationRequest.create("owner", "EXT-1", "CUST-1", "INTERNET-500", "corr", T0);
	}

	@Test
	void startsPendingWithNoAttempts() {
		ActivationRequest request = newRequest();

		assertThat(request.getId()).isNotNull();
		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(request.getAttempts()).isZero();
		assertThat(request.getNextAttemptAt()).isEqualTo(T0);
		assertThat(request.getCreatedAt()).isEqualTo(T0);
		assertThat(request.getUpdatedAt()).isEqualTo(T0);
	}

	@Test
	void startProcessingCountsAttempt() {
		ActivationRequest request = newRequest();

		request.startProcessing(T1);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
		assertThat(request.getAttempts()).isEqualTo(1);
		assertThat(request.getProcessingStartedAt()).isEqualTo(T1);
		assertThat(request.getUpdatedAt()).isEqualTo(T1);
	}

	@Test
	void completeFinishesProcessing() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);

		request.complete(T1);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
		assertThat(request.getProcessingStartedAt()).isNull();
	}

	@Test
	void failRecordsReasonAndTruncatesIt() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);

		request.fail("x".repeat(1000), T1);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
		assertThat(request.getFailureReason()).hasSize(ActivationRequest.MAX_REASON_LENGTH);
	}

	@Test
	void scheduleRetryReturnsToPendingKeepingTheAttempt() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);

		request.scheduleRetry("timeout", T1.plusSeconds(30), T1);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(request.getAttempts()).isEqualTo(1);
		assertThat(request.getNextAttemptAt()).isEqualTo(T1.plusSeconds(30));
		assertThat(request.getFailureReason()).isEqualTo("timeout");
	}

	@Test
	void releaseDoesNotConsumeAttempt() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);

		request.release("circuito aberto", T1, T1);

		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
		assertThat(request.getAttempts()).isZero();
	}

	@Test
	void completedCannotGoBackToProcessing() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);
		request.complete(T0);

		assertThatThrownBy(() -> request.startProcessing(T1)).isInstanceOf(InvalidStatusTransitionException.class)
				.hasMessageContaining("COMPLETED -> PROCESSING");
	}

	@Test
	void pendingCannotBeCompletedWithoutProcessing() {
		ActivationRequest request = newRequest();

		assertThatThrownBy(() -> request.complete(T1)).isInstanceOf(InvalidStatusTransitionException.class);
		assertThatThrownBy(() -> request.fail("x", T1)).isInstanceOf(InvalidStatusTransitionException.class);
		assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
	}

	@Test
	void failedIsTerminal() {
		ActivationRequest request = newRequest();
		request.startProcessing(T0);
		request.fail("recusado", T0);

		assertThatThrownBy(() -> request.scheduleRetry("x", T1, T1))
				.isInstanceOf(InvalidStatusTransitionException.class);
	}

	@Test
	void comparesContentIncludingOwner() {
		ActivationRequest request = newRequest();

		assertThat(request.hasSameContentAs("owner", "CUST-1", "INTERNET-500")).isTrue();
		assertThat(request.hasSameContentAs("owner", "CUST-1", "TV-100")).isFalse();
		assertThat(request.hasSameContentAs("other", "CUST-1", "INTERNET-500")).isFalse();
	}
}
