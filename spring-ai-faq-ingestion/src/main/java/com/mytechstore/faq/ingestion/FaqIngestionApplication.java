package com.mytechstore.faq.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI FAQ Ingestion Service - Main Application Entry Point
 *
 * Multi-tenant FAQ document ingestion and RAG service supporting:
 * - Multiple customer organizations
 * - Multiple document formats (PDF, MD, YAML, DOCX, Images)
 * - Automatic document structure detection
 * - ChromaDB vector store integration
 * - OpenAI embeddings and LLM integration
 *
 * Architecture:
 * - REST API for document upload and query
 * - Document processing pipeline (parse → chunk → embed)
 * - ChromaDB for persistent vector storage
 * - H2 database for metadata (customers, documents, ingestion logs)
 */
@SpringBootApplication
public class FaqIngestionApplication {

	public static void main(String[] args) {
		SpringApplication.run(FaqIngestionApplication.class, args);
	}

}
