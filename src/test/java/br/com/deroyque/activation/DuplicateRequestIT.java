package br.com.deroyque.activation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.github.tomakehurst.wiremock.client.WireMock;

import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.RequestStatus;
import br.com.deroyque.activation.support.AbstractIntegrationTest;
import br.com.deroyque.activation.support.TestTokens;

/** Garantia de unicidade do externalId, inclusive sob concorrencia, contra o PostgreSQL real. */
class DuplicateRequestIT extends AbstractIntegrationTest {

	private static final String AUTH = TestTokens.bearer("sys-a", "requests:read requests:write");

	@BeforeEach
	void stubProvider() {
		PROVIDER.stubFor(WireMock.post(urlEqualTo("/provider/v1/activate")).willReturn(aResponse().withStatus(200)));
	}

	private MockHttpServletResponse send(String auth, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/requests").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	@Test
	void sameExternalIdAndContentReturnsTheExistingRecord() throws Exception {
		MockHttpServletResponse first = send(AUTH, json("EXT-1", "CUST-1", "INTERNET-500"));
		MockHttpServletResponse second = send(AUTH, json("EXT-1", "CUST-1", "INTERNET-500"));

		assertThat(first.getStatus()).isEqualTo(201);
		assertThat(second.getStatus()).isEqualTo(200);
		assertThat(read(second.getContentAsString(), "$.id")).isEqualTo(read(first.getContentAsString(), "$.id"));
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void sameExternalIdWithDifferentProductIsConflict() throws Exception {
		send(AUTH, json("EXT-2", "CUST-1", "INTERNET-500"));

		MockHttpServletResponse response = send(AUTH, json("EXT-2", "CUST-1", "TV-100"));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(read(response.getContentAsString(), "$.type")).endsWith("/duplicate-request");
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void externalIdOfAnotherConsumerIsConflictAndLeaksNothing() throws Exception {
		send(AUTH, json("EXT-3", "CUST-1", "INTERNET-500"));

		MockHttpServletResponse response = send(TestTokens.bearer("sys-b", "requests:write"),
				json("EXT-3", "CUST-1", "INTERNET-500"));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString()).doesNotContain("sys-a").doesNotContain("CUST-1");
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void simultaneousIdenticalRequestsCreateExactlyOneRecordAndOneProviderCall() throws Exception {
		int threads = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return send(AUTH, json("EXT-RACE", "CUST-1", "INTERNET-500"));
			}));
		}
		ready.await();
		go.countDown();

		List<Integer> statuses = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (Future<MockHttpServletResponse> future : futures) {
			MockHttpServletResponse response = future.get();
			statuses.add(response.getStatus());
			ids.add(read(response.getContentAsString(), "$.id"));
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(threads - 1);
		assertThat(ids).containsOnly(ids.get(0)); // todos recebem o mesmo registro
		assertThat(repository.count()).isEqualTo(1);

		await().untilAsserted(() -> assertThat(repository.findAll()).extracting(ActivationRequest::getStatus)
				.containsExactly(RequestStatus.COMPLETED));
		PROVIDER.verify(1, postRequestedFor(urlEqualTo("/provider/v1/activate")));
	}
}
