package br.com.deroyque.activation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Observador do evento {@link ActivationFinished}, executado apos o commit.
 * Hoje registra metricas; e aqui que um publicador de eventos (fila/topico)
 * seria plugado para avisar outros microsservicos.
 */
@Component
public class ActivationFinishedListener {

	private static final Logger log = LoggerFactory.getLogger(ActivationFinishedListener.class);

	private final MeterRegistry meterRegistry;

	public ActivationFinishedListener(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(ActivationFinished event) {
		meterRegistry.counter("activation.requests.finished", "status", event.status().name()).increment();
		log.info("Solicitacao {} finalizada com status {}", event.requestId(), event.status());
	}
}
