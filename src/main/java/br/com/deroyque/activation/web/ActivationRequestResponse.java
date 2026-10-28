package br.com.deroyque.activation.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.RequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Solicitacao de ativacao e seu status atual")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationRequestResponse(
		UUID id,
		String externalId,
		String customerId,
		String productCode,
		RequestStatus status,
		@Schema(description = "Tentativas de envio ao provedor ja realizadas") int attempts,
		@Schema(description = "Motivo da ultima falha (presente em FAILED ou ao aguardar nova tentativa)") String failureReason,
		@Schema(description = "Data de criacao em UTC (ISO-8601)", example = "2026-10-26T14:03:11Z") Instant createdAt,
		@Schema(description = "Data da ultima atualizacao em UTC (ISO-8601)", example = "2026-10-26T14:03:11Z") Instant updatedAt) {

	public static ActivationRequestResponse from(ActivationRequest request) {
		return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
				request.getProductCode(), request.getStatus(), request.getAttempts(), request.getFailureReason(),
				request.getCreatedAt(), request.getUpdatedAt());
	}
}
