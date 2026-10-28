package br.com.deroyque.activation.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.deroyque.activation.domain.ActivationRequestRepository;
import br.com.deroyque.activation.domain.RequestStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publica quantas solicitacoes estao aguardando ({@code PENDING}) e em
 * andamento ({@code PROCESSING}). Sao os unicos estados cobertos por indice
 * parcial; contar os terminais exigiria varrer a tabela, entao eles sao
 * medidos por contador de transicoes (activation.requests.finished).
 */
@Component
public class BacklogMetrics {

	private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

	private final ActivationRequestRepository repository;
	private final Map<RequestStatus, AtomicLong> values = new EnumMap<>(RequestStatus.class);

	public BacklogMetrics(ActivationRequestRepository repository, MeterRegistry meterRegistry) {
		this.repository = repository;
		for (RequestStatus status : new RequestStatus[] { RequestStatus.PENDING, RequestStatus.PROCESSING }) {
			AtomicLong value = new AtomicLong();
			values.put(status, value);
			Gauge.builder("activation.requests.backlog", value, AtomicLong::get).tag("status", status.name())
					.description("Solicitacoes aguardando ou em processamento").register(meterRegistry);
		}
	}

	@Scheduled(fixedDelayString = "${activation.metrics.refresh-interval:PT15S}")
	public void refresh() {
		try {
			values.forEach((status, value) -> value.set(repository.countByStatus(status)));
		} catch (RuntimeException e) {
			log.warn("Nao foi possivel atualizar as metricas de backlog: {}", e.getMessage());
		}
	}
}
