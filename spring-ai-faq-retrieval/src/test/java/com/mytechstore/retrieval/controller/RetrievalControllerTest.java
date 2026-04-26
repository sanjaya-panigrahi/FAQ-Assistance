package com.mytechstore.retrieval.controller;

import com.mytechstore.retrieval.dto.RetrievalQueryRequest;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;
import com.mytechstore.retrieval.security.JwtTokenProvider;
import com.mytechstore.retrieval.service.RetrievalPipelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("RetrievalController Integration Tests")
@WebMvcTest(RetrievalController.class)
@AutoConfigureMockMvc(addFilters = false)
class RetrievalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetrievalPipelineService retrievalPipelineService;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private final String baseUrl = "/api";

    @Test
    @DisplayName("Should return 200 OK for /actuator/health")
    void testHealthCheck() throws Exception {
        mockMvc.perform(get(baseUrl + "/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should support V1 API endpoint")
    void testQueryV1Endpoint() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "What is FAQ?",
            "transformed query",
            "hybrid-retrieval",
            "This is an answer",
            1,
            true,
            100,
            50,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/v1/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "What is FAQ?"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenantId", equalTo("tenant-1")))
            .andExpect(jsonPath("$.question", equalTo("What is FAQ?")))
            .andExpect(jsonPath("$.answer", equalTo("This is an answer")));
    }

    @Test
    @DisplayName("Should support V2 API endpoint with X-Request-ID")
    void testQueryV2WithRequestId() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "What is Spring AI?",
            "transformed query",
            "hybrid-retrieval",
            "Spring AI is a framework",
            1,
            true,
            120,
            60,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/v2/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-ID", "req-123")
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "What is Spring AI?"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.question", equalTo("What is Spring AI?")));
    }

    @Test
    @DisplayName("Should support Idempotency-Key header in V2")
    void testQueryV2WithIdempotencyKey() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "Test query",
            "transformed",
            "hybrid-retrieval",
            "Test answer",
            1,
            true,
            100,
            50,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/v2/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "test-123")
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "Test query"
                }
                """))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should redirect legacy /api/retrieval/query to V1")
    void testLegacyEndpointRedirection() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "Legacy query",
            "transformed",
            "hybrid-retrieval",
            "Legacy answer",
            1,
            true,
            100,
            50,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "Legacy query"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.question", equalTo("Legacy query")));
    }

    @Test
    @DisplayName("Should reject invalid request with 400")
    void testInvalidRequestValidation() throws Exception {
        mockMvc.perform(post(baseUrl + "/v1/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "tenant-1"
                }
                """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should support custom top-k parameter")
    void testCustomTopKParameter() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "Query",
            "transformed",
            "hybrid-retrieval",
            "Answer",
            5,
            true,
            100,
            50,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/v1/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "Query",
                  "topK": 20
                }
                """))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should include query context in request")
    void testQueryWithContext() throws Exception {
        RetrievalQueryResponse mockResponse = new RetrievalQueryResponse(
            "tenant-1",
            "Query with context",
            "transformed",
            "hybrid-retrieval",
            "Answer",
            1,
            true,
            100,
            50,
            Collections.emptyList(),
            false,
            false,
            "weighted"
        );

        when(retrievalPipelineService.query(any(RetrievalQueryRequest.class)))
            .thenReturn(mockResponse);

        mockMvc.perform(post(baseUrl + "/v1/retrieval/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "tenant-1",
                  "question": "Query with context",
                  "queryContext": "User is looking for FAQ"
                }
                """))
            .andExpect(status().isOk());
    }
}
