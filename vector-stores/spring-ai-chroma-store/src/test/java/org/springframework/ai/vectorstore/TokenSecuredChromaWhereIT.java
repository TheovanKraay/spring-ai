/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ChromaImage;
import org.springframework.ai.chroma.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.chromadb.ChromaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ChromaDB with static API Token Authentication:
 * https://docs.trychroma.com/deployment/auth
 *
 * Test cases are based on the Chroma:
 * https://docs.trychroma.com/usage-guide#using-where-filters and the related
 * https://github.com/chroma-core/chroma/blob/main/examples/basic_functionality/in_not_in_filtering.ipynb
 *
 * @author Christian Tzolov
 * @author Eddú Meléndez
 * @author Thomas Vitale
 */
@Testcontainers
public class TokenSecuredChromaWhereIT {

	public static String CHROMA_SERVER_AUTH_CREDENTIALS = "test-token";

	/**
	 * ChromaDB with static API Token Authentication:
	 * https://docs.trychroma.com/deployment/auth
	 */
	@Container
	static ChromaDBContainer chromaContainer = new ChromaDBContainer(ChromaImage.DEFAULT_IMAGE)
		.withEnv("CHROMA_SERVER_AUTHN_CREDENTIALS", CHROMA_SERVER_AUTH_CREDENTIALS)
		.withEnv("CHROMA_SERVER_AUTHN_PROVIDER", "chromadb.auth.token_authn.TokenAuthenticationServerProvider");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class)
		.withPropertyValues("spring.ai.openai.apiKey=" + System.getenv("OPENAI_API_KEY"));

	@Test
	public void withInFiltersExpressions1() {

		contextRunner.run(context -> {

			VectorStore vectorStore = context.getBean(VectorStore.class);

			vectorStore.add(List.of(new Document("1", "Article by john", Map.of("author", "john")),
					new Document("2", "Article by Jack", Map.of("author", "jack")),
					new Document("3", "Article by Jill", Map.of("author", "jill"))));

			var request = SearchRequest.query("Give me articles by john").withTopK(5);

			List<Document> results = vectorStore.similaritySearch(request);
			assertThat(results).hasSize(3);

			results = vectorStore.similaritySearch(
					request.withSimilarityThresholdAll().withFilterExpression("author in ['john', 'jill']"));

			assertThat(results).hasSize(2);
			assertThat(results.stream().map(d -> d.getId()).toList()).containsExactlyInAnyOrder("1", "3");
		});
	}

	@Test
	public void withInFiltersExpressions() {

		contextRunner.run(context -> {

			VectorStore vectorStore = context.getBean(VectorStore.class);

			vectorStore
				.add(List.of(new Document("1", "Article by john", Map.of("author", "john", "article_type", "blog")),
						new Document("2", "Article by Jack", Map.of("author", "jack", "article_type", "social")),
						new Document("3", "Article by Jill", Map.of("author", "jill", "article_type", "paper"))));

			var request = SearchRequest.query("Give me articles by john").withTopK(5);

			List<Document> results = vectorStore.similaritySearch(request);
			assertThat(results).hasSize(3);

			results = vectorStore.similaritySearch(request.withSimilarityThresholdAll()
				.withFilterExpression("author in ['john', 'jill'] && 'article_type' == 'blog'"));

			assertThat(results).hasSize(1);
			assertThat(results.get(0).getId()).isEqualTo("1");

			results = vectorStore.similaritySearch(request.withSimilarityThresholdAll()
				.withFilterExpression("author in ['john'] || 'article_type' == 'paper'"));

			assertThat(results).hasSize(2);

			assertThat(results.stream().map(d -> d.getId()).toList()).containsExactlyInAnyOrder("1", "3");
		});
	}

	@SpringBootConfiguration
	public static class TestApplication {

		@Bean
		public RestClient.Builder builder() {
			return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
		}

		@Bean
		public ChromaApi chromaApi(RestClient.Builder builder) {
			var chromaApi = new ChromaApi(chromaContainer.getEndpoint(), builder);
			chromaApi.withKeyToken(CHROMA_SERVER_AUTH_CREDENTIALS);
			return chromaApi;
		}

		@Bean
		public VectorStore chromaVectorStore(EmbeddingModel embeddingModel, ChromaApi chromaApi) {
			return new ChromaVectorStore(embeddingModel, chromaApi, "TestCollection", true);
		}

		@Bean
		public EmbeddingModel embeddingModel() {
			return new OpenAiEmbeddingModel(new OpenAiApi(System.getenv("OPENAI_API_KEY")));
		}

	}

}
