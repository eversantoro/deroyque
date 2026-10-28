package br.com.deroyque.activation.application;

import java.util.UUID;

import br.com.deroyque.activation.domain.ActivationRequest;

/**
 * Dados enviados ao provedor externo. {@code requestId} e usado como chave de
 * idempotencia: reenviar a mesma solicitacao nunca ativa o produto duas vezes.
 */
public record ProviderRequest(
		UUID requestId,
		String externalId,
		String customerId,
		String productCode,
		String correlationId,
		int attempt) {

	public static ProviderRequest from(ActivationRequest request) {
		return new ProviderRequest(request.getId(), request.getExternalId(), request.getCustomerId(),
				request.getProductCode(), request.getCorrelationId(), request.getAttempts());
	}
}
