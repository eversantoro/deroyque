package br.com.deroyque.activation.application;

import java.util.UUID;

import br.com.deroyque.activation.domain.RequestStatus;

/**
 * Evento de dominio publicado quando uma solicitacao chega a um estado
 * terminal. Ponto de extensao para avisar outros servicos (fila/topico) sem
 * acoplar o processador a eles.
 */
public record ActivationFinished(UUID requestId, String externalId, RequestStatus status, String reason) {
}
