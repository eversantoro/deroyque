package br.com.deroyque.activation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RequestStatusTest {

	@Test
	void pendingOnlyGoesToProcessing() {
		assertThat(allowedFrom(RequestStatus.PENDING)).containsExactly(RequestStatus.PROCESSING);
	}

	@Test
	void processingCanCompleteFailOrReturnToPending() {
		assertThat(allowedFrom(RequestStatus.PROCESSING)).containsExactlyInAnyOrder(RequestStatus.COMPLETED,
				RequestStatus.FAILED, RequestStatus.PENDING);
	}

	@ParameterizedTest
	@EnumSource(value = RequestStatus.class, names = { "COMPLETED", "FAILED" })
	void terminalStatesGoNowhere(RequestStatus terminal) {
		assertThat(terminal.isTerminal()).isTrue();
		assertThat(allowedFrom(terminal)).isEmpty();
	}

	private static Set<RequestStatus> allowedFrom(RequestStatus from) {
		Set<RequestStatus> allowed = new java.util.HashSet<>();
		for (RequestStatus target : RequestStatus.values()) {
			if (from.canTransitionTo(target)) {
				allowed.add(target);
			}
		}
		return allowed;
	}
}
