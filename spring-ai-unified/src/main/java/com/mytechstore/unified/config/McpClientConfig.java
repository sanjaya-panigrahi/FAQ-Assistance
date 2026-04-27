package com.mytechstore.unified.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Client configuration for Spring AI Unified service.
 * <p>
 * When {@code mcp.enabled=true}, provides an {@link McpToolClient} bean that
 * enables pipeline services to call MCP server tools via JSON-RPC 2.0.
 * When disabled (default), this configuration is skipped and existing direct
 * clients continue to operate.
 */
@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    @Value("${mcp.servers.chromadb.url:http://mcp-chromadb:8301}")
    private String chromadbUrl;

    @Value("${mcp.servers.neo4j.url:http://mcp-neo4j:8302}")
    private String neo4jUrl;

    @Value("${mcp.servers.llm-gateway.url:http://mcp-llm-gateway:8303}")
    private String llmGatewayUrl;

    @Value("${mcp.servers.web-search.url:http://mcp-web-search:8304}")
    private String webSearchUrl;

    @Value("${mcp.servers.cache.url:http://mcp-cache:8305}")
    private String cacheUrl;

    @Value("${mcp.servers.analytics.url:http://mcp-analytics:8306}")
    private String analyticsUrl;

    @Value("${mcp.api-key:}")
    private String apiKey;

    @Bean
    public McpToolClient mcpToolClient() {
        log.info("Initializing MCP Tool Client with servers: chromadb={}, neo4j={}, llm-gateway={}, web-search={}, cache={}, analytics={}",
                chromadbUrl, neo4jUrl, llmGatewayUrl, webSearchUrl, cacheUrl, analyticsUrl);
        return new McpToolClient(
                chromadbUrl, neo4jUrl, llmGatewayUrl,
                webSearchUrl, cacheUrl, analyticsUrl, apiKey
        );
    }

    /**
     * HTTP-based MCP tool client that calls MCP servers via JSON-RPC 2.0.
     */
    public static class McpToolClient {

        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicInteger requestIdCounter = new AtomicInteger(0);
        private final Map<String, RestClient> clients;

        public McpToolClient(
                String chromadbUrl, String neo4jUrl, String llmGatewayUrl,
                String webSearchUrl, String cacheUrl, String analyticsUrl,
                String apiKey
        ) {
            RestClient.Builder builder = RestClient.builder()
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

            if (apiKey != null && !apiKey.isEmpty()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            this.clients = Map.of(
                    "chromadb", builder.clone().baseUrl(chromadbUrl).build(),
                    "neo4j", builder.clone().baseUrl(neo4jUrl).build(),
                    "llm_gateway", builder.clone().baseUrl(llmGatewayUrl).build(),
                    "web_search", builder.clone().baseUrl(webSearchUrl).build(),
                    "cache", builder.clone().baseUrl(cacheUrl).build(),
                    "analytics", builder.clone().baseUrl(analyticsUrl).build()
            );
        }

        /**
         * Call an MCP tool on a specific server.
         *
         * @param serverName MCP server name (chromadb, neo4j, llm_gateway, etc.)
         * @param toolName   Name of the tool to invoke
         * @param arguments  Tool arguments as a Map
         * @return Parsed JSON result from the MCP tool
         */
        public JsonNode callTool(String serverName, String toolName, Map<String, Object> arguments) {
            RestClient client = clients.get(serverName);
            if (client == null) {
                throw new IllegalArgumentException("Unknown MCP server: " + serverName);
            }

            int requestId = requestIdCounter.incrementAndGet();
            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", toolName,
                            "arguments", arguments != null ? arguments : Map.of()
                    )
            );

            try {
                String responseBody = client.post()
                        .uri("/mcp")
                        .body(payload)
                        .retrieve()
                        .body(String.class);

                JsonNode root = mapper.readTree(responseBody);
                if (root.has("error")) {
                    log.warn("MCP tool error: {}/{} -> {}", serverName, toolName, root.get("error"));
                    return root.get("error");
                }

                JsonNode result = root.path("result");
                JsonNode content = result.path("content");
                if (content.isArray() && !content.isEmpty()) {
                    String text = content.get(0).path("text").asText("{}");
                    return mapper.readTree(text);
                }
                return result;
            } catch (Exception e) {
                log.error("MCP call failed: {}/{}: {}", serverName, toolName, e.getMessage());
                return mapper.createObjectNode().put("error", e.getMessage());
            }
        }

        // ─── Convenience methods ─────────────────────────────────────────

        public JsonNode similaritySearch(String query, String tenantId, int topK) {
            return callTool("chromadb", "similarity_search", Map.of(
                    "query_texts", List.of(query),
                    "tenant_id", tenantId,
                    "top_k", topK
            ));
        }

        public JsonNode generateChat(List<Map<String, String>> messages, double temperature) {
            return callTool("llm_gateway", "generate_chat", Map.of(
                    "messages", messages,
                    "temperature", temperature
            ));
        }

        public JsonNode graphSearch(String query) {
            return callTool("neo4j", "fulltext_search", Map.of("query", query));
        }

        public JsonNode webSearch(String query, int maxResults) {
            return callTool("web_search", "web_search", Map.of(
                    "query", query,
                    "max_results", maxResults
            ));
        }

        public JsonNode cacheGet(String pipeline, String tenantId, String question) {
            return callTool("cache", "get_cached_response", Map.of(
                    "pipeline", pipeline,
                    "tenant_id", tenantId,
                    "question", question
            ));
        }

        public JsonNode cacheSet(String pipeline, String tenantId, String question, String data) {
            return callTool("cache", "cache_response", Map.of(
                    "pipeline", pipeline,
                    "tenant_id", tenantId,
                    "question", question,
                    "response_data", data
            ));
        }

        public JsonNode logAnalytics(Map<String, Object> event) {
            return callTool("analytics", "log_rag_event", event);
        }
    }
}
