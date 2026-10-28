package br.com.deroyque.activation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.ActivationRequestRepository;
import br.com.deroyque.activation.domain.RequestStatus;

/**
 * Leva solicitacoes pendentes ao provedor em tres passos, sem nunca manter uma
 * transacao aberta durante a chamada HTTP:
 *
 * <ol>
 * <li>{@link #claimDue(int)}: transacao curta que reserva as linhas
 * (PENDING -> PROCESSING);</li>
 * <li>{@link #process(ProviderRequest)}: chamada ao provedor, fora de
 * transacao;</li>
 * <li>aplicacao do resultado em outra transacao curta.</li>
 * </ol>
 *
 * Se a aplicacao cair entre os passos, a linha continua no banco e o
 * {@link StalledRequestReconciler} a devolve para a fila.
 */
@Component
public class ActivationProcessor {

	private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);
	public static final String CORRELATION_ID_KEY = "correlationId";

	private final ActivationRequestRepository repository;
	private final TransactionTemplate transactionTemplate;
	private final ActivationProvider provider;
	private final BackoffPolicy backoffPolicy;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final ActivationProperties properties;

	public ActivationProcessor(ActivationRequestRepository repository, TransactionTemplate transactionTemplate,
			ActivationProvider provider, BackoffPolicy backoffPolicy, ApplicationEventPublisher events, Clock clock,
			ActivationProperties properties) {
		this.repository = repository;
		this.transactionTemplate = transactionTemplate;
		this.provider = provider;
		this.backoffPolicy = backoffPolicy;
		this.events = events;
		this.clock = clock;
		this.properties = properties;
	}

	/** Reserva ate {@code limit} solicitacoes prontas e as marca como PROCESSING. */
	public List<ProviderRequest> claimDue(int limit) {
		return transactionTemplate.execute(status -> {
			Instant now = now();
			return repository.lockDueForProcessing(now, limit).stream().map(request -> {
				request.startProcessing(now);
				return ProviderRequest.from(request);
			}).toList();
		});
	}

	/** Chama o provedor e registra o resultado. Nao lanca excecao. */
	public void process(ProviderRequest request) {
		if (request.correlationId() != null) {
			MDC.put(CORRELATION_ID_KEY, request.correlationId());
		}
		try {
			ProviderOutcome outcome = provider.activate(request);
			transactionTemplate.executeWithoutResult(status -> apply(request, outcome));
		} catch (ObjectOptimisticLockingFailureException e) {
			log.warn("Solicitacao {} alterada por outro processo; resultado descartado", request.requestId());
		} catch (RuntimeException e) {
			// Fica em PROCESSING e sera recuperada pelo reconciliador.
			log.error("Falha inesperada ao processar a solicitacao {}", request.requestId(), e);
		} finally {
			MDC.remove(CORRELATION_ID_KEY);
		}
	}

	private void apply(ProviderRequest sent, ProviderOutcome outcome) {
		ActivationRequest request = repository.findById(sent.requestId()).orElse(null);
		if (request == null || request.getStatus() != RequestStatus.PROCESSING
				|| request.getAttempts() != sent.attempt()) {
			log.warn("Solicitacao {} mudou durante a chamada ao provedor; resultado ignorado", sent.requestId());
			return;
		}
		Instant now = now();
		switch (outcome) {
			case ProviderOutcome.Activated activated -> {
				request.complete(now);
				log.info("Solicitacao {} concluida na tentativa {}", request.getId(), request.getAttempts());
				publishFinished(request);
			}
			case ProviderOutcome.Rejected rejected -> {
				request.fail(rejected.reason(), now);
				log.warn("Solicitacao {} recusada pelo provedor: {}", request.getId(), rejected.reason());
				publishFinished(request);
			}
			case ProviderOutcome.RetryLater retry -> retryOrFail(request, retry, now);
			case ProviderOutcome.Unavailable unavailable -> {
				request.release(unavailable.reason(), now.plus(unavailable.retryAfter()), now);
				log.warn("Provedor indisponivel; solicitacao {} volta para a fila: {}", request.getId(),
						unavailable.reason());
			}
		}
	}

	private void retryOrFail(ActivationRequest request, ProviderOutcome.RetryLater retry, Instant now) {
		if (request.getAttempts() >= properties.retry().maxAttempts()) {
			request.fail("Tentativas esgotadas (" + request.getAttempts() + "): " + retry.reason(), now);
			log.error("Solicitacao {} falhou apos {} tentativas: {}", request.getId(), request.getAttempts(),
					retry.reason());
			publishFinished(request);
			return;
		}
		Duration delay = backoffPolicy.delayAfter(request.getAttempts());
		if (retry.retryAfter() != null) {
			// respeita o Retry-After do provedor, limitado ao teto configurado
			Duration suggested = retry.retryAfter().compareTo(properties.retry().maxDelay()) > 0
					? properties.retry().maxDelay()
					: retry.retryAfter();
			delay = delay.compareTo(suggested) > 0 ? delay : suggested;
		}
		request.scheduleRetry(retry.reason(), now.plus(delay), now);
		log.warn("Solicitacao {} sera reenviada em {} ms (tentativa {} falhou): {}", request.getId(),
				delay.toMillis(), request.getAttempts(), retry.reason());
	}

	private void publishFinished(ActivationRequest request) {
		events.publishEvent(new ActivationFinished(request.getId(), request.getExternalId(), request.getStatus(),
				request.getFailureReason()));
	}

	private Instant now() {
		return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
	}
}
