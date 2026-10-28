package br.com.deroyque.activation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivationRequestRepository
		extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

	Optional<ActivationRequest> findByExternalId(String externalId);

	Optional<ActivationRequest> findByIdAndOwnerId(UUID id, String ownerId);

	long countByStatus(RequestStatus status);

	/**
	 * Reserva solicitacoes prontas para envio. {@code SKIP LOCKED} permite varias
	 * instancias consumindo a "fila" sem disputar as mesmas linhas.
	 */
	@Query(value = """
			select * from activation_request
			 where status = 'PENDING' and next_attempt_at <= :now
			 order by next_attempt_at
			 limit :limit
			 for update skip locked
			""", nativeQuery = true)
	List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

	/** Reserva solicitacoes presas em PROCESSING (a instancia que as pegou caiu). */
	@Query(value = """
			select * from activation_request
			 where status = 'PROCESSING' and processing_started_at < :threshold
			 order by processing_started_at
			 limit :limit
			 for update skip locked
			""", nativeQuery = true)
	List<ActivationRequest> lockStalled(@Param("threshold") Instant threshold, @Param("limit") int limit);
}
