package com.mytechstore.graphrag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.graphrag.dto.RagRequest;
import com.mytechstore.graphrag.dto.RagResponse;
import com.mytechstore.graphrag.service.GraphPipelineService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("GraphRagController Integration Tests")
@WebMvcTest(GraphRagController.class)
@AutoConfigureMockMvc(addFilters = false)
class GraphRagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GraphPipelineService pipelineService;

    @MockBean
    private ChatClient chatClient;

    @MockBean
    private VectorStore vectorStore;

    @Test
    @DisplayName("POST /api/index/rebuild returns 200 with status")
    void rebuildIndex_returnsOk() throws Exception {
        when(pipelineService.rebuildIndex()).thenReturn("rebuilt");

        mockMvc.perform(post("/api/index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("rebuilt")));
    }

    @Test
    @DisplayName("POST /api/query/ask returns 200 with graph answer")
    void ask_validRequest_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Graph answer via Neo4j", 4, 2, "neo4j-graph", "graph-rag");
        when(pipelineService.ask(eq("What products are related to laptop?"), any())).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(
                new RagRequest("What products are related to laptop?", "customer-5"));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Graph answer via Neo4j")))
                .andExpect(jsonPath("$.vectorChunks", is(4)))
                .andExpect(jsonPath("$.graphFacts", is(2)))
                .andExpect(jsonPath("$.strategy", is("neo4j-graph")))
                .andExpect(jsonPath("$.orchestrationStrategy", is("graph-rag")));
    }

    @Test
    @DisplayName("POST /api/query/ask with null customerId succeeds")
    void ask_nullCustomerId_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Answer", 1, 1, "neo4j-graph", "graph-rag");
        when(pipelineService.ask(any(), eq(null))).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("How does the graph work?", null));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/query/ask with blank question returns 400")
    void ask_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\",\"customerId\":\"c1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask with missing question returns 400")
    void ask_missingQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask with question exceeding 500 chars returns 400")
    void ask_questionTooLong_returns400() throws Exception {
        String longQuestion = "Q".repeat(501);
        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + longQuestion + "\",\"customerId\":\"c1\"}"))
                .andExpect(status().isBadRequest());
    }
}
