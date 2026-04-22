package com.mytechstore.agentic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.agentic.dto.RagRequest;
import com.mytechstore.agentic.dto.RagResponse;
import com.mytechstore.agentic.service.AgenticPipelineService;
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

@DisplayName("AgenticRagController Integration Tests")
@WebMvcTest(AgenticRagController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgenticRagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgenticPipelineService pipelineService;

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
    @DisplayName("POST /api/query/ask returns 200 with answer")
    void ask_validRequest_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("This is the answer", "agentic", 3, "agentic-rag");
        when(pipelineService.ask(eq("What is the return policy?"), any())).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("What is the return policy?", "customer-1"));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("This is the answer")))
                .andExpect(jsonPath("$.strategy", is("agentic")))
                .andExpect(jsonPath("$.chunksUsed", is(3)))
                .andExpect(jsonPath("$.orchestrationStrategy", is("agentic-rag")));
    }

    @Test
    @DisplayName("POST /api/query/ask with null customerId uses null")
    void ask_nullCustomerId_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Answer without customer", "agentic", 1, "agentic-rag");
        when(pipelineService.ask(any(), eq(null))).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("How to return a product?", null));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/query/ask with blank question returns 400")
    void ask_blankQuestion_returns400() throws Exception {
        String body = "{\"question\":\"\",\"customerId\":\"c1\"}";

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask with missing question returns 400")
    void ask_missingQuestion_returns400() throws Exception {
        String body = "{\"customerId\":\"c1\"}";

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask with question exceeding 500 chars returns 400")
    void ask_questionTooLong_returns400() throws Exception {
        String longQuestion = "Q".repeat(501);
        String body = "{\"question\":\"" + longQuestion + "\",\"customerId\":\"c1\"}";

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
