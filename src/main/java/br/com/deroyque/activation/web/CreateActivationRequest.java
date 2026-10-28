package br.com.deroyque.activation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Dados para solicitar a ativacao de um produto")
public record CreateActivationRequest(

		@Schema(description = "Identificador da solicitacao no sistema de origem; unico no servico", example = "EXT-12345", maxLength = 64, pattern = Patterns.IDENTIFIER)
		@NotBlank(message = "é obrigatório")
		@Size(max = 64, message = "deve ter no máximo {max} caracteres")
		@Pattern(regexp = Patterns.IDENTIFIER, message = Patterns.IDENTIFIER_MESSAGE)
		String externalId,

		@Schema(description = "Identificador do cliente", example = "CUST-10001", maxLength = 64, pattern = Patterns.IDENTIFIER)
		@NotBlank(message = "é obrigatório")
		@Size(max = 64, message = "deve ter no máximo {max} caracteres")
		@Pattern(regexp = Patterns.IDENTIFIER, message = Patterns.IDENTIFIER_MESSAGE)
		String customerId,

		@Schema(description = "Codigo do produto a ativar", example = "INTERNET-500", maxLength = 64, pattern = Patterns.IDENTIFIER)
		@NotBlank(message = "é obrigatório")
		@Size(max = 64, message = "deve ter no máximo {max} caracteres")
		@Pattern(regexp = Patterns.IDENTIFIER, message = Patterns.IDENTIFIER_MESSAGE)
		String productCode) {

	/** Expressao compartilhada pelos tres campos (constante para uso em anotacoes). */
	static final class Patterns {

		static final String IDENTIFIER = "^([A-Za-z0-9][A-Za-z0-9._:-]*)?$";
		static final String IDENTIFIER_MESSAGE = "formato inválido: use letras, números, '.', '_', ':' ou '-'";

		private Patterns() {
		}
	}
}
