package br.com.deroyque.activation.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.deroyque.activation.config.ActivationProperties;

/** Consulta periodicamente a fila e despacha as chamadas ao provedor em paralelo. */
@Component
@ConditionalOnProperty(name = "activation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

	private final ActivationProcessor processor;
	private final ExecutorService executor;
	private final int batchSize;

	public ActivationDispatcher(ActivationProcessor processor, ExecutorService providerCallExecutor,
			ActivationProperties properties) {
		this.processor = processor;
		this.executor = providerCallExecutor;
		this.batchSize = properties.worker().batchSize();
	}

	@Scheduled(fixedDelayString = "${activation.worker.poll-interval:PT1S}")
	public void poll() {
		try {
			List<ProviderRequest> batch = processor.claimDue(batchSize);
			if (batch.isEmpty()) {
				return;
			}
			log.debug("Despachando {} solicitacoes ao provedor", batch.size());
			// Aguarda o lote terminar: o proximo ciclo so comeca depois, o que limita a
			// concorrencia ao tamanho do lote e permite desligamento gracioso.
			CompletableFuture.allOf(batch.stream()
					.map(request -> CompletableFuture.runAsync(() -> processor.process(request), executor))
					.toArray(CompletableFuture[]::new)).join();
		} catch (RuntimeException e) {
			log.error("Falha no ciclo de despacho; sera repetido no proximo intervalo", e);
		}
	}
}
