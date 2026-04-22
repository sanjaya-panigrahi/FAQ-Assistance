package com.mytechstore.vision.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.vision.dto.VisionRagRequest;
import com.mytechstore.vision.dto.VisionRagResponse;
import com.mytechstore.vision.service.OpenAiVisionExtractor;
import com.mytechstore.vision.service.OpenAiVisionExtractor.ConsistencyResult;
import com.mytechstore.vision.service.VisionPipelineService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("VisionRagController Integration Tests")
@WebMvcTest(VisionRagController.class)
@AutoConfigureMockMvc(addFilters = false)
class VisionRagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VisionPipelineService pipelineService;

    @MockBean
    private OpenAiVisionExtractor visionExtractor;

        @MockBean
        private ChatClient chatClient;

        @MockBean
        private VectorStore vectorStore;

    private VisionRagResponse sampleResponse() {
        return new VisionRagResponse("Vision answer", 3, "multimodal", "vision-rag", "match", 0.9, List.of("product visible"));
    }

    @Test
    @DisplayName("POST /api/index/rebuild returns 200 with status")
    void rebuildIndex_returnsOk() throws Exception {
        when(pipelineService.rebuildIndex()).thenReturn("rebuilt");

        mockMvc.perform(post("/api/index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("rebuilt")));
    }

    @Test
    @DisplayName("POST /api/query/ask returns 200 with vision answer")
    void ask_validRequest_returnsAnswer() throws Exception {
        when(pipelineService.ask(eq("Is the product damaged?"), any(), any())).thenReturn(sampleResponse());

        String body = objectMapper.writeValueAsString(
                new VisionRagRequest("Is the product damaged?", "customer-4", "Box looks dented"));

        mockMvc.perform(post("/api/query/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("Vision answer")))
                .andExpect(jsonPath("$.strategy", is("multimodal")))
                .andExpect(jsonPath("$.consistencyLabel", is("match")))
                .andExpect(jsonPath("$.consistencyScore", is(0.9)));
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
    @DisplayName("POST /api/query/ask-with-image returns 200 for valid image")
    void askWithImage_validImage_returnsAnswer() throws Exception {
        when(visionExtractor.extractImageSignals(any(), any())).thenReturn("product looks intact");
        when(visionExtractor.evaluateConsistency(any(), any()))
                .thenReturn(new ConsistencyResult("match", 0.85, List.of("product intact")));
        when(pipelineService.ask(any(), any(), any())).thenReturn(sampleResponse());

        MockMultipartFile image = new MockMultipartFile(
                "image", "product.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());

        mockMvc.perform(multipart("/api/query/ask-with-image")
                        .file(image)
                        .param("question", "Is this product defective?")
                        .param("customerId", "customer-1")
                        .param("imageDescription", "Cracked screen visible"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/query/ask-with-image with blank question returns 400")
    void askWithImage_blankQuestion_returns400() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "product.jpg", "image/jpeg", "fake-jpeg-bytes".getBytes());

        mockMvc.perform(multipart("/api/query/ask-with-image")
                        .file(image)
                        .param("question", "   ")
                        .param("customerId", "customer-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask-with-image with non-image file returns 400")
    void askWithImage_nonImageFile_returns400() throws Exception {
        MockMultipartFile badFile = new MockMultipartFile(
                "image", "document.pdf", "application/pdf", "pdf-content".getBytes());

        mockMvc.perform(multipart("/api/query/ask-with-image")
                        .file(badFile)
                        .param("question", "What is this?")
                        .param("customerId", "customer-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/query/ask-with-image without image still works")
    void askWithImage_noImage_returnsAnswer() throws Exception {
        when(visionExtractor.evaluateConsistency(any(), any()))
                .thenReturn(new ConsistencyResult("match", 1.0, List.of()));
        when(pipelineService.ask(any(), any(), any())).thenReturn(sampleResponse());

        mockMvc.perform(multipart("/api/query/ask-with-image")
                        .param("question", "What is the return policy?")
                        .param("imageDescription", "Product looks good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", notNullValue()));
    }
}
