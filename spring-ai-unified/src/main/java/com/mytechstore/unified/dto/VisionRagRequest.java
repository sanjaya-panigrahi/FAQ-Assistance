package com.mytechstore.unified.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

public record VisionRagRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = 500, message = "question must be 500 characters or fewer")
        String question,
        @Size(max = 100, message = "customerId must be 100 characters or fewer")
        String customerId,
        @Size(max = 1000, message = "imageDescription must be 1000 characters or fewer")
        String imageDescription) implements Serializable {
    private static final long serialVersionUID = 1L;
}
