package br.com.deroyque.activation.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;

import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.ActivationRequestRepository;
import br.com.deroyque.activation.domain.RequestStatus;

/**
 * Base dos testes de integracao: PostgreSQL real (Testcontainers), provedor
 * simulado (WireMock) e a aplicacao completa, inclusive o worker assincrono.
 * Container e WireMock sao iniciados uma unica vez para toda a suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles({ "local", "it" })
public abstract class AbstractIntegrationTest {

	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
	protected static final WireMockServer PROVIDER = new WireMockServer(options().dynamicPort());

	static {
		POSTGRES.start();
		PROVIDER.start();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("activation.provider.base-url", PROVIDER::baseUrl);
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ActivationRequestRepository repository;

	private static volatile boolean warmedUp;

	@BeforeEach
	void resetState() {
		Awaitility.setDefaultTimeout(Duration.ofSeconds(20));
		Awaitility.setDefaultPollInterval(Duration.ofMillis(100));
		warmUpOnce();
		PROVIDER.resetAll();
		repository.deleteAllInBatch();
	}

	/**
	 * A primeira chamada HTTP da JVM (carga de classes do cliente e do WireMock) pode
	 * estourar o read-timeout curto dos testes; este aquecimento evita falso negativo.
	 */
	private void warmUpOnce() {
		if (warmedUp) {
			return;
		}
		PROVIDER.stubFor(WireMock.post(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(200)));
		ActivationRequest warmup = ActivationRequest.create("warmup", "WARMUP", "CUST", "PRODUCT", "warmup",
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		repository.saveAndFlush(warmup);
		Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(repository.findById(warmup.getId()))
				.get().extracting(ActivationRequest::getStatus).isEqualTo(RequestStatus.COMPLETED));
		warmedUp = true;
	}

	protected static String json(String externalId, String customerId, String productCode) {
		return "{\"externalId\":\"%s\",\"customerId\":\"%s\",\"productCode\":\"%s\"}".formatted(externalId,
				customerId, productCode);
	}

	protected static String read(String responseBody, String path) {
		Object value = JsonPath.read(responseBody, path);
		return value == null ? null : value.toString();
	}
}
