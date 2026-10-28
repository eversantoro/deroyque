package br.com.deroyque.activation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.ActivationRequestRepository;
import br.com.deroyque.activation.domain.RequestStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;

/**
 * Casos de uso de registro e consulta de solicitacoes.
 *
 * <p>
 * A unicidade de {@code externalId} e garantida pelo banco (indice unico). Em
 * vez de "consultar e depois inserir" (que tem janela de corrida), tentamos
 * inserir e tratamos a violacao de unicidade como o caso "ja existe".
 */
@Service
public class ActivationRequestService {

	private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

	private final ActivationRequestRepository repository;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;
	private final MeterRegistry meterRegistry;

	public ActivationRequestService(ActivationRequestRepository repository, TransactionTemplate transactionTemplate,
			Clock clock, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Registra a solicitacao ou devolve a ja existente quando e uma repeticao
	 * legitima (mesmo consumidor, mesmo conteudo).
	 *
	 * @throws DuplicateExternalIdException se o externalId ja existe com conteudo
	 *                                      diferente ou pertence a outro consumidor
	 */
	public CreationResult create(CreateRequestCommand command, String ownerId) {
		Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
		ActivationRequest candidate = ActivationRequest.create(ownerId, command.externalId(), command.customerId(),
				command.productCode(), command.correlationId(), now);
		try {
			// A transacao termina aqui: a violacao de unicidade aparece no commit/flush e
			// e tratada fora dela (em PostgreSQL a transacao abortada nao pode mais ler).
			ActivationRequest saved = transactionTemplate.execute(status -> repository.saveAndFlush(candidate));
			meterRegistry.counter("activation.requests.created").increment();
			log.info("Solicitacao {} registrada (externalId={})", saved.getId(), saved.getExternalId());
			return new CreationResult(saved, true);
		} catch (DataIntegrityViolationException e) {
			ActivationRequest existing = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
			if (!existing.hasSameContentAs(ownerId, command.customerId(), command.productCode())) {
				log.warn("externalId {} ja existe com conteudo diferente ou de outro consumidor",
						command.externalId());
				throw new DuplicateExternalIdException(command.externalId());
			}
			log.info("Repeticao do externalId {} devolve a solicitacao {}", command.externalId(), existing.getId());
			return new CreationResult(existing, false);
		}
	}

	@Transactional(readOnly = true)
	public ActivationRequest get(UUID id, String ownerId) {
		// Solicitacao de outro consumidor e tratada como inexistente (evita BOLA).
		return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new RequestNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public Page<ActivationRequest> search(String ownerId, RequestStatus status, String customerId, int page,
			int size) {
		Specification<ActivationRequest> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("ownerId"), ownerId));
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (customerId != null && !customerId.isBlank()) {
				predicates.add(cb.equal(root.get("customerId"), customerId));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		return repository.findAll(spec, pageable);
	}

	public record CreateRequestCommand(String externalId, String customerId, String productCode,
			String correlationId) {
	}

	/** {@code created=false} indica repeticao legitima de uma solicitacao existente. */
	public record CreationResult(ActivationRequest request, boolean created) {
	}
}
