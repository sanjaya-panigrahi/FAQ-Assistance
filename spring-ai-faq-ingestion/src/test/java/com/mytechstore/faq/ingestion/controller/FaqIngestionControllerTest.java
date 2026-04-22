package com.mytechstore.faq.ingestion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.faq.ingestion.dto.CustomerCreateRequest;
import com.mytechstore.faq.ingestion.dto.CustomerResponse;
import com.mytechstore.faq.ingestion.dto.FaqQueryRequest;
import com.mytechstore.faq.ingestion.dto.FaqQueryResponse;
import com.mytechstore.faq.ingestion.service.ChromaDBService;
import com.mytechstore.faq.ingestion.service.CustomerService;
import com.mytechstore.faq.ingestion.service.RagService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("FaqIngestionController Integration Tests")
@WebMvcTest(FaqIngestionController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"app.chroma.persist-directory=/tmp/test-chroma"})
class FaqIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerService customerService;

    @MockBean
    private RagService ragService;

    @MockBean
    private ChromaDBService chromadbService;

    private CustomerResponse sampleCustomer() {
        return CustomerResponse.builder()
                .id(1L)
                .customerId("cust-123")
                .name("Test Corp")
                .description("A test customer")
                .contactEmail("test@example.com")
                .isActive(true)
                .documentCount(0)
                .indexedChunksCount(0L)
                .collectionName("cust-123-faqs")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /api/faq-ingestion/customers returns 201 with created customer")
    void createCustomer_validRequest_returns201() throws Exception {
        when(customerService.createCustomer(any(CustomerCreateRequest.class))).thenReturn(sampleCustomer());

        String body = objectMapper.writeValueAsString(
                CustomerCreateRequest.builder()
                        .customerId("cust-123")
                        .name("Test Corp")
                        .description("A test customer")
                        .contactEmail("test@example.com")
                        .build());

        mockMvc.perform(post("/api/faq-ingestion/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId", is("cust-123")))
                .andExpect(jsonPath("$.name", is("Test Corp")))
                .andExpect(jsonPath("$.contactEmail", is("test@example.com")));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/customers returns 200 with list")
    void listCustomers_returnsOkWithList() throws Exception {
        when(customerService.listCustomers()).thenReturn(List.of(sampleCustomer()));

        mockMvc.perform(get("/api/faq-ingestion/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerId", is("cust-123")));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/customers/{id} returns 200 for existing customer")
    void getCustomer_existingId_returnsOk() throws Exception {
        when(customerService.getCustomer("cust-123")).thenReturn(sampleCustomer());

        mockMvc.perform(get("/api/faq-ingestion/customers/cust-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId", is("cust-123")))
                .andExpect(jsonPath("$.name", is("Test Corp")));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/customers/{id} returns 404 for unknown customer")
    void getCustomer_unknownId_returns404() throws Exception {
        when(customerService.getCustomer("unknown")).thenThrow(new IllegalArgumentException("Customer not found"));

        mockMvc.perform(get("/api/faq-ingestion/customers/unknown"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/faq-ingestion/customers/{id} returns 200 on update")
    void updateCustomer_validRequest_returnsOk() throws Exception {
        CustomerResponse updated = sampleCustomer();
        when(customerService.updateCustomer(eq("cust-123"), any(CustomerCreateRequest.class))).thenReturn(updated);

        String body = objectMapper.writeValueAsString(
                CustomerCreateRequest.builder()
                        .name("Updated Corp")
                        .contactEmail("updated@example.com")
                        .build());

        mockMvc.perform(put("/api/faq-ingestion/customers/cust-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId", is("cust-123")));
    }

    @Test
    @DisplayName("DELETE /api/faq-ingestion/customers/{id} returns 204")
    void deleteCustomer_existingId_returns204() throws Exception {
        doNothing().when(customerService).deleteCustomer("cust-123");

        mockMvc.perform(delete("/api/faq-ingestion/customers/cust-123"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/faq-ingestion/query returns 200 with answer")
    void queryFaq_validRequest_returnsAnswer() throws Exception {
        FaqQueryResponse faqResponse = FaqQueryResponse.builder()
                .question("What is the return policy?")
                .answer("30-day return policy")
                .totalSourcesUsed(2)
                .averageSimilarity(0.87)
                .build();
        when(ragService.queryFaq(any(FaqQueryRequest.class))).thenReturn(faqResponse);

        String body = objectMapper.writeValueAsString(
                FaqQueryRequest.builder()
                        .customerId("cust-123")
                        .question("What is the return policy?")
                        .topK(5)
                        .similarityThreshold(0.7)
                        .build());

        mockMvc.perform(post("/api/faq-ingestion/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question", is("What is the return policy?")))
                .andExpect(jsonPath("$.answer", is("30-day return policy")))
                .andExpect(jsonPath("$.totalSourcesUsed", is(2)));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/health returns 200 with UP status when chroma healthy")
    void getHealth_chromaUp_returnsUp() throws Exception {
        when(chromadbService.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/faq-ingestion/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/health returns 200 with DEGRADED when chroma down")
    void getHealth_chromaDown_returnsDegraded() throws Exception {
        when(chromadbService.isHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/faq-ingestion/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DEGRADED")));
    }

    @Test
    @DisplayName("GET /api/faq-ingestion/info returns 200 with service info")
    void getInfo_returnsOk() throws Exception {
        mockMvc.perform(get("/api/faq-ingestion/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", notNullValue()));
    }
}
