package com.mytechstore.guardrails.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.guardrails.dto.RagRequest;
import com.mytechstore.guardrails.dto.RagResponse;
import com.mytechstore.guardrails.service.GuardrailPipelineService;
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

@DisplayName("GuardrailController Integration Tests")
@WebMvcTest(GuardrailController.class)
@AutoConfigureMockMvc(addFilters = false)
class GuardrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GuardrailPipelineService pipelineService;

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
    @DisplayName("POST /api/query/ask returns 200 with corrective answer")
    void ask_validRequest_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Corrective answer", false, "ok", 4, "corrective",
            "corrective-rag");
        when(pipelineService.ask(eq("What is the warranty?"), any())).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("What is the warranty?", "customer-2"));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Corrective answer")))
            .andExpect(jsonPath("$.blocked", is(false)))
            .andExpect(jsonPath("$.reason", is("ok")))
                .andExpect(jsonPath("$.strategy", is("corrective")))
                .andExpect(jsonPath("$.chunksUsed", is(4)))
                .andExpect(jsonPath("$.orchestrationStrategy", is("corrective-rag")));
    }

    @Test
    @DisplayName("POST /api/query/ask with null customerId succeeds")
    void ask_nullCustomerId_returnsAnswer() throws Exception {
        RagResponse mockResponse = new RagResponse("Answer", false, "ok", 2, "corrective", "corrective-rag");
        when(pipelineService.ask(any(), eq(null))).thenReturn(mockResponse);

        String body = objectMapper.writeValueAsString(new RagRequest("What is the shipping policy?", null));

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
        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c1\"}"))
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
