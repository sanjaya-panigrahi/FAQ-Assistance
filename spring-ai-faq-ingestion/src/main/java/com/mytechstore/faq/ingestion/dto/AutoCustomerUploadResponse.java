package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoCustomerUploadResponse {
    private String customerId;
    private String customerName;
    private boolean customerCreated;
    private String detectionSource;
    private DocumentResponse document;
}
