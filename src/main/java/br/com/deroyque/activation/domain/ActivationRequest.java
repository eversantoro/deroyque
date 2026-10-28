package br.com.deroyque.activation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Solicitacao de ativacao de um produto. As mudancas de estado so ocorrem por
 * metodos de negocio, que validam a transicao; nao ha setters publicos.
 *
 * <p>
 * A propria linha da solicitacao funciona como fila de trabalho (outbox):
 * {@code PENDING} com {@code nextAttemptAt} vencido significa "ainda precisa
 * ser enviada ao provedor".
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

	public static final int MAX_REASON_LENGTH = 255;

	@Id
	private UUID id;

	@Column(name = "external_id", nullable = false, updatable = false, length = 64)
	private String externalId;

	@Column(name = "customer_id", nullable = false, updatable = false, length = 64)
	private String customerId;

	@Column(name = "product_code", nullable = false, updatable = false, length = 64)
	private String productCode;

	@Column(name = "owner_id", nullable = false, updatable = false, length = 128)
	private String ownerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RequestStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "processing_started_at")
	private Instant processingStartedAt;

	@Column(name = "failure_reason", length = MAX_REASON_LENGTH)
	private String failureReason;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected ActivationRequest() {
		// exigido pelo JPA
	}

	public static ActivationRequest create(String ownerId, String externalId, String customerId, String productCode,
			String correlationId, Instant now) {
		ActivationRequest request = new ActivationRequest();
		request.id = UUID.randomUUID();
		request.ownerId = ownerId;
		request.externalId = externalId;
		request.customerId = customerId;
		request.productCode = productCode;
		request.correlationId = correlationId;
		request.status = RequestStatus.PENDING;
		request.attempts = 0;
		request.nextAttemptAt = now;
		request.createdAt = now;
		request.updatedAt = now;
		return request;
	}

	/** PENDING -> PROCESSING. Conta uma tentativa. */
	public void startProcessing(Instant now) {
		transitionTo(RequestStatus.PROCESSING, now);
		attempts++;
		processingStartedAt = now;
	}

	/** PROCESSING -> COMPLETED. */
	public void complete(Instant now) {
		transitionTo(RequestStatus.COMPLETED, now);
		processingStartedAt = null;
		failureReason = null;
	}

	/** PROCESSING -> FAILED. Estado terminal. */
	public void fail(String reason, Instant now) {
		transitionTo(RequestStatus.FAILED, now);
		processingStartedAt = null;
		failureReason = truncate(reason);
	}

	/** PROCESSING -> PENDING, mantendo a tentativa consumida (falha temporaria). */
	public void scheduleRetry(String reason, Instant retryAt, Instant now) {
		transitionTo(RequestStatus.PENDING, now);
		processingStartedAt = null;
		nextAttemptAt = retryAt;
		failureReason = truncate(reason);
	}

	/**
	 * PROCESSING -> PENDING sem penalizar: a chamada nem chegou a ser feita (por
	 * exemplo, circuito aberto), entao a tentativa nao e contada.
	 */
	public void release(String reason, Instant retryAt, Instant now) {
		scheduleRetry(reason, retryAt, now);
		attempts = Math.max(0, attempts - 1);
	}

	public boolean hasSameContentAs(String otherOwnerId, String otherCustomerId, String otherProductCode) {
		return ownerId.equals(otherOwnerId) && customerId.equals(otherCustomerId)
				&& productCode.equals(otherProductCode);
	}

	private void transitionTo(RequestStatus target, Instant now) {
		if (!status.canTransitionTo(target)) {
			throw new InvalidStatusTransitionException(status, target);
		}
		status = target;
		updatedAt = now;
	}

	private static String truncate(String reason) {
		if (reason == null || reason.length() <= MAX_REASON_LENGTH) {
			return reason;
		}
		return reason.substring(0, MAX_REASON_LENGTH);
	}

	public UUID getId() {
		return id;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getCustomerId() {
		return customerId;
	}

	public String getProductCode() {
		return productCode;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public RequestStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getProcessingStartedAt() {
		return processingStartedAt;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}
}
