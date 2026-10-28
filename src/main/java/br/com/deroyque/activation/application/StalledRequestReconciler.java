package br.com.deroyque.activation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.ActivationRequestRepository;

/**
 * Reconciliacao: solicitacoes que ficaram em PROCESSING alem do limite (a
 * instancia caiu entre reservar e registrar o resultado) voltam para a fila.
 * Reenviar e seguro porque o provedor recebe a mesma chave de idempotencia.
 */
@Component
@ConditionalOnProperty(name = "activation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class StalledRequestReconciler {

	private static final Logger log = LoggerFactory.getLogger(StalledRequestReconciler.class);

	private final ActivationRequestRepository repository;
	private final TransactionTemplate transactionTemplate;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final ActivationProperties properties;

	public StalledRequestReconciler(ActivationRequestRepository repository, TransactionTemplate transactionTemplate,
			ApplicationEventPublisher events, Clock clock, ActivationProperties properties) {
		this.repository = repository;
		this.transactionTemplate = transactionTemplate;
		this.events = events;
		this.clock = clock;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${activation.reconciliation.interval:PT30S}")
	public void scheduled() {
		try {
			reconcile();
		} catch (RuntimeException e) {
			log.error("Falha na reconciliacao; sera repetida no proximo intervalo", e);
		}
	}

	/** @return quantidade de solicitacoes recuperadas */
	public int reconcile() {
		Integer recovered = transactionTemplate.execute(status -> {
			Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
			Instant threshold = now.minus(properties.reconciliation().staleAfter());
			List<ActivationRequest> stalled = repository.lockStalled(threshold,
					properties.reconciliation().batchSize());
			stalled.forEach(request -> recover(request, now));
			return stalled.size();
		});
		return recovered == null ? 0 : recovered;
	}

	private void recover(ActivationRequest request, Instant now) {
		String reason = "Processamento interrompido antes de registrar o resultado";
		if (request.getAttempts() >= properties.retry().maxAttempts()) {
			request.fail(reason + "; tentativas esgotadas", now);
			events.publishEvent(new ActivationFinished(request.getId(), request.getExternalId(),
					request.getStatus(), request.getFailureReason()));
			log.error("Solicitacao {} presa em PROCESSING e sem tentativas restantes", request.getId());
		} else {
			request.scheduleRetry(reason, now, now);
			log.warn("Solicitacao {} presa em PROCESSING devolvida para a fila", request.getId());
		}
	}
}
