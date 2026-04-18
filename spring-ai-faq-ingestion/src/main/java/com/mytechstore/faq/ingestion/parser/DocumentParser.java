package com.mytechstore.faq.ingestion.parser;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Contract for all document parsers.
 */
public interface DocumentParser {
    String extractText(MultipartFile file) throws IOException;

    boolean supports(String fileName);
}