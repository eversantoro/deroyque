package br.com.deroyque.activation.application;

/** Porta de saida para o servico externo de ativacao. */
public interface ActivationProvider {

	/**
	 * Solicita a ativacao. Nunca lanca excecao por falha do provedor: toda falha
	 * esperada vira um {@link ProviderOutcome}.
	 */
	ProviderOutcome activate(ProviderRequest request);
}
