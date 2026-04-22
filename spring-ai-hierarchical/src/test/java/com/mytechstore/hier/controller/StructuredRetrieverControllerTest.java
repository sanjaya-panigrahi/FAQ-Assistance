package com.mytechstore.hier.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.hier.dto.RagRequest;
import com.mytechstore.hier.dto.RagResponse;
import com.mytechstore.hier.service.StructuredPipelineService;
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

@DisplayName("StructuredRetrieverController Integration Tests")
@WebMvcTest(StructuredRetrieverController.class)
@AutoConfigureMockMvc(addFilters = false)
class StructuredRetrieverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StructuredPipelineService pipelineService;

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
    @DisplayName("POST /api/query/ask returns 200 with hierarchical answer")
    void ask_validRequest_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Hierarchical answer", "orders", 5, "hierarchical",
            "hierarchical-rag");
        when(pipelineService.ask(eq("How do I track my order?"), any())).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("How do I track my order?", "customer-3"));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Hierarchical answer")))
                .andExpect(jsonPath("$.selectedSection", is("orders")))
                .andExpect(jsonPath("$.strategy", is("hierarchical")))
                .andExpect(jsonPath("$.chunksUsed", is(5)))
                .andExpect(jsonPath("$.orchestrationStrategy", is("hierarchical-rag")));
    }

    @Test
    @DisplayName("POST /api/query/ask with null customerId succeeds")
    void ask_nullCustomerId_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Answer", "general", 2, "hierarchical", "hierarchical-rag");
        when(pipelineService.ask(any(), eq(null))).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("What payment methods are accepted?", null));

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
