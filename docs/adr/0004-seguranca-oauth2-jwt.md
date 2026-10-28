# ADR 0004 — Segurança: OAuth 2.0 Resource Server com JWT

- **Status:** aceita

## Decisão

A API é um **Resource Server** (Spring Security): ela só valida tokens; a emissão fica com um servidor de autorização
externo (Keycloak, Entra ID, Auth0, Cognito...).

- **Fluxo:** *client credentials*, o adequado para comunicação entre sistemas (não há usuário final; cada sistema tem
  `client_id` e segredo próprios, e o token é de curta duração).
- **O que é validado:** assinatura (chaves públicas do JWKS em `JWT_JWK_SET_URI`, com cache e rotação automática),
  emissor (`iss`), audiência (`aud` deve conter `activation-service`) e expiração/`nbf`.
- **Autorização por escopo:** `requests:write` cria (`POST`); `requests:read` consulta (`GET`); `metrics:read` acessa
  `/actuator/prometheus`. Qualquer outra rota é negada por padrão. 401 sem token/token inválido; 403 com escopo
  insuficiente — ambos em `application/problem+json`.
- **Isolamento entre consumidores (BOLA, OWASP API1):** toda solicitação grava o dono (`client_id` → `azp` → `sub`).
  Consulta e listagem filtram por dono; acessar solicitação de outro consumidor devolve **404** (não 403, para não
  revelar a existência).
- **Limites:** corpo máximo de 8 KB (413, inclusive para requisições *chunked*) e rate limit por consumidor com *token
  bucket* (429 + `Retry-After`).

## Perfil `local`

Para desenvolver sem servidor de autenticação, o perfil `local` troca a fonte de chaves por um segredo HS256
(`JWT_LOCAL_SECRET`). **Nunca em produção** — por isso o perfil padrão exige `JWT_JWK_SET_URI` e a aplicação não sobe
sem ele. Os testes emitem JWTs reais assinados com esse segredo, exercitando a validação completa sem mocks.

## Credencial para chamar o provedor

Hoje enviada como `X-Api-Key` a partir de `PROVIDER_API_KEY`, nunca logada. Em produção: guardar em um cofre
(AWS Secrets Manager / Azure Key Vault / HashiCorp Vault / Kubernetes Secret com criptografia em repouso), injetar como
variável de ambiente no start e **rotacionar** sem redeploy: aceitar a chave antiga e a nova durante a janela de troca
no provedor, ou ler o segredo do cofre periodicamente. Preferível trocar a chave estática por OAuth 2.0 *client
credentials* também nessa chamada (token curto, renovado automaticamente).

## Limitações conhecidas

- O rate limit é por instância (memória). Para um limite global, aplicar no API Gateway ou usar um contador
  compartilhado (Redis).
