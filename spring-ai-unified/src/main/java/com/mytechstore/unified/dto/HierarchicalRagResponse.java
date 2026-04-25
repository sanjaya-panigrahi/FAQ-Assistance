package com.mytechstore.unified.dto;

import java.io.Serializable;

public record HierarchicalRagResponse(String answer, String selectedSection, int chunksUsed, String strategy,
        String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}
