package br.com.deroyque.activation.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import br.com.deroyque.activation.application.ActivationRequestService;
import br.com.deroyque.activation.application.ActivationRequestService.CreateRequestCommand;
import br.com.deroyque.activation.application.ActivationRequestService.CreationResult;
import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.RequestStatus;
import br.com.deroyque.activation.observability.CorrelationIdFilter;
import br.com.deroyque.activation.security.ConsumerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/requests")
@Tag(name = "Solicitações de ativação")
public class ActivationRequestController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final ActivationRequestService service;

	public ActivationRequestController(ActivationRequestService service) {
		this.service = service;
	}

	@PostMapping
	@Operation(summary = "Cria uma solicitação de ativação", description = """
			Registra a solicitação com status PENDING; o envio ao provedor é assíncrono.
			O externalId é único: repetir a mesma solicitação (mesmo conteúdo) devolve 200 com o registro existente;
			reutilizar o externalId com conteúdo diferente devolve 409. Requer o escopo `requests:write`.
			""")
	@ApiResponse(responseCode = "201", description = "Solicitação criada")
	@ApiResponse(responseCode = "200", description = "Repetição legítima: devolve a solicitação já existente")
	@ApiResponse(responseCode = "400", description = "Dados inválidos", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "Token ausente ou inválido", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Escopo insuficiente", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "externalId já utilizado com conteúdo diferente", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "413", description = "Corpo maior que o limite", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "Limite de requisições excedido", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
			@AuthenticationPrincipal Jwt jwt) {
		CreateRequestCommand command = new CreateRequestCommand(body.externalId(), body.customerId(),
				body.productCode(), CorrelationIdFilter.current());
		CreationResult result = service.create(command, ConsumerIdentity.ownerOf(jwt));
		ActivationRequestResponse response = ActivationRequestResponse.from(result.request());
		URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").build(response.id());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).location(location)
				.body(response);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consulta uma solicitação", description = "Requer o escopo `requests:read`. Solicitações de outros consumidores são tratadas como inexistentes.")
	@ApiResponse(responseCode = "200", description = "Solicitação encontrada")
	@ApiResponse(responseCode = "404", description = "Solicitação inexistente", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return ActivationRequestResponse.from(service.get(id, ConsumerIdentity.ownerOf(jwt)));
	}

	@GetMapping
	@Operation(summary = "Lista solicitações com filtros e paginação", description = "Ordenadas da mais recente para a mais antiga. Requer o escopo `requests:read`.")
	public PageResponse<ActivationRequestResponse> list(
			@Parameter(description = "Filtra por status") @RequestParam(required = false) RequestStatus status,
			@Parameter(description = "Filtra por cliente") @RequestParam(required = false) @Size(max = 64, message = "deve ter no máximo {max} caracteres") String customerId,
			@Parameter(description = "Página (começa em 0)") @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser maior ou igual a 0") int page,
			@Parameter(description = "Tamanho da página (1 a 100)") @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser no mínimo 1") @Max(value = 100, message = "deve ser no máximo 100") int size,
			@AuthenticationPrincipal Jwt jwt) {
		Page<ActivationRequest> result = service.search(ConsumerIdentity.ownerOf(jwt), status, customerId, page, size);
		return PageResponse.of(result, ActivationRequestResponse::from);
	}
}
