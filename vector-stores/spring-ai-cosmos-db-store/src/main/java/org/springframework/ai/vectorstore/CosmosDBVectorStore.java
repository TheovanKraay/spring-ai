package org.springframework.ai.vectorstore;

import com.azure.cosmos.*;
import com.azure.cosmos.implementation.guava25.collect.ImmutableList;
import com.azure.cosmos.models.*;
import com.azure.cosmos.util.CosmosPagedFlux;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.observation.conventions.VectorStoreProvider;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.SearchRequest;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CosmosDBVectorStore extends AbstractObservationVectorStore implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(CosmosDBVectorStore.class);
	private final CosmosAsyncClient cosmosClient;
	private CosmosAsyncContainer container;
	private final EmbeddingModel embeddingModel;

	public CosmosDBVectorStore(ObservationRegistry observationRegistry,
							   VectorStoreObservationConvention customObservationConvention,
							   CosmosAsyncClient cosmosClient,
							   CosmosDBVectorStoreConfig properties,
							   EmbeddingModel embeddingModel) {
		super(observationRegistry, customObservationConvention);
		this.cosmosClient = cosmosClient;
		cosmosClient.createDatabaseIfNotExists(properties.databaseName).block();

		initializeContainer(properties.containerName, properties.databaseName);

		this.embeddingModel = embeddingModel;

	}

	private void initializeContainer(String containerName, String databaseName) {

		CosmosContainerProperties collectionDefinition = new CosmosContainerProperties(containerName, "/id");
		// Set vector embedding policy if needed, similar to Cassandra vector settings
		CosmosVectorEmbeddingPolicy embeddingPolicy = new CosmosVectorEmbeddingPolicy();
		CosmosVectorEmbedding embedding = new CosmosVectorEmbedding();
		embedding.setPath("/embedding");
		embedding.setDataType(CosmosVectorDataType.FLOAT32);
		embedding.setDimensions(384L); // Example, set based on actual model dimensions
		embedding.setDistanceFunction(CosmosVectorDistanceFunction.COSINE);
		embeddingPolicy.setCosmosVectorEmbeddings(Collections.singletonList(embedding));
		collectionDefinition.setVectorEmbeddingPolicy(embeddingPolicy);

		//set vector indexing policy
		IndexingPolicy indexingPolicy = new IndexingPolicy();
		indexingPolicy.setIndexingMode(IndexingMode.CONSISTENT);
		ExcludedPath excludedPath = new ExcludedPath("/*");
		indexingPolicy.setExcludedPaths(Collections.singletonList(excludedPath));
		IncludedPath includedPath1 = new IncludedPath("/metadata/?");
		IncludedPath includedPath2 = new IncludedPath("/content/?");
		indexingPolicy.setIncludedPaths(ImmutableList.of(includedPath1, includedPath2));
		CosmosVectorIndexSpec cosmosVectorIndexSpec = new CosmosVectorIndexSpec();
		cosmosVectorIndexSpec.setPath("/embedding");
		cosmosVectorIndexSpec.setType(CosmosVectorIndexType.DISK_ANN.toString());
		indexingPolicy.setVectorIndexes(List.of(cosmosVectorIndexSpec));
		collectionDefinition.setIndexingPolicy(indexingPolicy);


		ThroughputProperties throughputProperties = ThroughputProperties.createManualThroughput(400);
		CosmosAsyncDatabase cosmosAsyncDatabase = cosmosClient.getDatabase(databaseName);
		cosmosAsyncDatabase.createContainerIfNotExists(collectionDefinition, throughputProperties).block();
		this.container = cosmosAsyncDatabase.getContainer(containerName);
	}

	public static class CosmosDBVectorStoreConfig {
		private String containerName;
		private String databaseName;
		private String partitionKeyPath;
		private String endpoint;
		private String key;

		public String getEndpoint() {
			return endpoint;
		}

		public void setEndpoint(String endpoint) {
			this.endpoint = endpoint;
		}

		public String getKey() {
			return key;
		}

		public void setKey(String key) {
			this.key = key;
		}

		public String getContainerName() {
			return containerName;
		}

		public void setContainerName(String containerName) {
			this.containerName = containerName;
		}

		public String getDatabaseName() {
			return databaseName;
		}

		public void setDatabaseName(String databaseName) {
			this.databaseName = databaseName;
		}

		public String getPartitionKeyPath() {
			return partitionKeyPath;
		}

		public void setPartitionKeyPath(String partitionKeyPath) {
			this.partitionKeyPath = partitionKeyPath;
		}
	}

	@Override
	public void close() {
		if (cosmosClient != null) {
			cosmosClient.close();
			logger.info("Cosmos DB client closed successfully.");
		}
	}

	private JsonNode mapCosmosDocument(Document document, float[] queryEmbedding) {
		ObjectMapper objectMapper = new ObjectMapper();

		String id = document.getId();
		String content = document.getContent();

		// Convert metadata and embedding directly to JsonNode
		JsonNode metadataNode = objectMapper.valueToTree(document.getMetadata());
		JsonNode embeddingNode = objectMapper.valueToTree(queryEmbedding);

		// Create an ObjectNode specifically
		ObjectNode objectNode = objectMapper.createObjectNode();

		// Use put for simple values and set for JsonNode values
		objectNode.put("id", id);
		objectNode.put("content", content);
		objectNode.set("metadata", metadataNode);  // Use set to add JsonNode directly
		objectNode.set("embedding", embeddingNode); // Use set to add JsonNode directly

		// Log each field of the ObjectNode for debugging
		objectNode.fields().forEachRemaining(entry -> logger.info("Node: {} = {}", entry.getKey(), entry.getValue()));

		return objectNode;
	}




	@Override
	public void doAdd(List<Document> documents) {

		List<CosmosItemOperation> itemOperations = documents.stream()
				.map(doc -> CosmosBulkOperations.getCreateItemOperation(mapCosmosDocument(doc, this.embeddingModel.embed(doc.getContent())), new PartitionKey(doc.getId())))
				.collect(Collectors.toList());

		try {

			container.executeBulkOperations(Flux.fromIterable(itemOperations))
					.doOnNext(response -> {
						// Check if the response or its content is null before accessing the status code
						if (response != null && response.getResponse() != null) {
							logger.info("Document added with status: {}", response.getResponse().getStatusCode());
						} else {
							logger.warn("Received a null response or null status code for a document operation.");
						}
					})
					.doOnError(error -> logger.error("Error adding document: {}", error.getMessage()))
					.doOnComplete(() -> logger.info("Bulk operation completed successfully."))
					.blockLast(); // Block until the last item of the Flux is processed
		} catch (Exception e) {
			logger.error("Exception occurred during bulk add operation: {}", e.getMessage(), e);
		}
	}




	@Override
	public Optional<Boolean> doDelete(List<String> idList) {
		try {
			List<CosmosItemOperation> itemOperations = idList.stream()
					.map(id -> CosmosBulkOperations.getDeleteItemOperation(id, new PartitionKey(id)))
					.collect(Collectors.toList());

			container.executeBulkOperations(Flux.fromIterable(itemOperations))
					.subscribe(
							response -> logger.info("Document deleted with status: {}", response.getResponse().getStatusCode()),
							error -> logger.error("Error deleting document: {}", error.getMessage())
					);
			return Optional.of(true);
		} catch (Exception e) {
			logger.error("Exception while deleting documents: {}", e.getMessage());
			return Optional.of(false);
		}
	}

	@Override
	public List<Document> similaritySearch(String query) {
		return similaritySearch(SearchRequest.query(query));
	}

	@Override
	public List<Document> doSimilaritySearch(SearchRequest request) {
		// Ensure topK is within acceptable limits
		if (request.getTopK() > 1000) {
			throw new IllegalArgumentException("Top K must be 1000 or less.");
		}

		// Convert query into vector embedding
		float[] embedding = this.embeddingModel.embed(request.getQuery());

		logger.info("similarity threshold: {}", request.getSimilarityThreshold());

		List<Float> embeddingList = IntStream.range(0, embedding.length)
				.mapToObj(i -> embedding[i])
				.collect(Collectors.toList());

		// Build query for similarity search
		String query = "SELECT TOP @topK * FROM c WHERE VectorDistance(c.embedding, @embedding) > @similarityThreshold ORDER BY VectorDistance(c.embedding, @embedding)";
		List<SqlParameter> parameters = new ArrayList<>();
		parameters.add(new SqlParameter("@embedding", embeddingList));
		parameters.add(new SqlParameter("@topK", request.getTopK()));
		parameters.add(new SqlParameter("@similarityThreshold", request.getSimilarityThreshold()));

		SqlQuerySpec sqlQuerySpec = new SqlQuerySpec(query, parameters);
		CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();

		CosmosPagedFlux<JsonNode> pagedFlux = container.queryItems(sqlQuerySpec, options, JsonNode.class);

		try {
			// Collect documents from the paged flux
			List<JsonNode> documents = pagedFlux.byPage()
					.flatMap(page -> Flux.fromIterable(page.getResults()))
					.collectList()
					.block();
			if (documents != null) {
				documents.forEach(doc -> logger.info("Found document with ID: {}", doc.get("id")));
			}
			// Convert JsonNode to Document
			List<Document> docs = documents.stream()
					.map(doc -> new Document(doc.get("id").asText(), doc.get("content").asText(), new HashMap<>()))
					.collect(Collectors.toList());

			return docs != null ? docs : List.of();
		} catch (Exception e) {
			logger.error("Error during similarity search: {}", e.getMessage());
			return List.of();
		}
	}

	@Override
	public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {
		return VectorStoreObservationContext.builder(VectorStoreProvider.COSMOSDB.value(),operationName)
				.withCollectionName(container.getId())
				.withDimensions(this.embeddingModel.dimensions())
				.withNamespace(container.getDatabase().getId())
				.withSimilarityMetric("cosine");
	}
}
