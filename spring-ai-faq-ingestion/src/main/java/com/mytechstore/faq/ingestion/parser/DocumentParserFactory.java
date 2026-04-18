package com.mytechstore.faq.ingestion.parser;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Document Parser Factory
 * Routes document parsing requests to appropriate parser based on file type
 *
 * Design Pattern: Factory Pattern
 * - Encapsulates parser selection logic
 * - Easy to add new document types
 * - Supports polymorphic parsing
 */
@Service
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;

    public DocumentParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * Extract text from uploaded document
     *
     * Strategy:
     * 1. Determine file type from filename extension
     * 2. Find matching parser from registry
     * 3. Delegate to parser
     * 4. Return extracted text
     *
     * @param file The uploaded file
     * @return Extracted text content
     * @throws IOException If file cannot be read
     * @throws IllegalArgumentException If file type not supported
     */
    public String extractText(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be null or empty");
        }

        DocumentParser parser = findParser(file.getOriginalFilename());
        if (parser == null) {
            throw new IllegalArgumentException(
                "Unsupported file type: " + file.getOriginalFilename() +
                ". Supported types: pdf, md, yaml, docx, txt, images (png, jpg, jpeg)"
            );
        }

        return parser.extractText(file);
    }

    /**
     * Determine if a file type is supported
     * @param fileName The file name with extension
     * @return true if supported
     */
    public boolean isSupported(String fileName) {
        return findParser(fileName) != null;
    }

    /**
     * Get the detected document type
     * @param fileName The file name
     * @return File extension/type
     */
    public String detectFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return extension;
    }

    /**
     * Find appropriate parser for file
     * @param fileName The file name
     * @return Parser instance or null if not found
     */
    private DocumentParser findParser(String fileName) {
        if (fileName == null) {
            return null;
        }

        return parsers.stream()
            .filter(p -> p.supports(fileName))
            .findFirst()
            .orElse(null);
    }

}
