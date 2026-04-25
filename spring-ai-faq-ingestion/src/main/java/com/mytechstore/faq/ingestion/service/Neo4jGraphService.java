package com.mytechstore.faq.ingestion.service;

import jakarta.annotation.PreDestroy;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indexes FAQ entries as graph nodes in Neo4j during the ingestion pipeline.
 * Creates FaqEntry nodes with a fulltext index for Graph RAG queries.
 */
@Service
public class Neo4jGraphService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphService.class);
    private static final Pattern QA_PATTERN = Pattern.compile(
            "\\d+\\.\\s*\\*\\*(.*?)\\*\\*\\s*\\n\\s*(.+?)(?=\\n\\s*\\d+\\.\\s*\\*\\*|\\Z)",
            Pattern.DOTALL
    );

    private final Driver driver;
    private final boolean enabled;

    public Neo4jGraphService(
            @Value("${app.neo4j.uri:bolt://neo4j-unified:7687}") String uri,
            @Value("${app.neo4j.username:neo4j}") String username,
            @Value("${app.neo4j.password:neo4jpass}") String password,
            @Value("${app.neo4j.enabled:true}") boolean enabled
    ) {
        this.enabled = enabled;
        if (enabled) {
            this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
            log.info("Neo4j graph indexing enabled at {}", uri);
        } else {
            this.driver = null;
            log.info("Neo4j graph indexing disabled");
        }
    }

    @PreDestroy
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }

    /**
     * Index FAQ entries from raw document text into Neo4j as FaqEntry nodes.
     * Parses Q&A pairs from markdown-style FAQ format, deletes existing entries
     * for the customer, and creates fresh nodes with a fulltext index.
     *
     * @param rawText     The full raw text of the ingested document
     * @param customerId  The customer identifier
     * @return number of FAQ entries indexed, or 0 if disabled/failed
     */
    public int indexFaqEntries(String rawText, String customerId) {
        if (!enabled || driver == null) {
            return 0;
        }

        List<Map<String, Object>> entries = parseFaqEntriesWithCustomer(rawText, customerId);
        if (entries.isEmpty()) {
            log.debug("No FAQ Q&A entries found in document for customer {}", customerId);
            return 0;
        }

        try (Session session = driver.session()) {
            // Delete existing entries for this customer
            session.run(
                    "MATCH (f:FaqEntry {customer_id: $customerId}) DETACH DELETE f",
                    Map.of("customerId", customerId)
            );

            // Create new entries
            session.run(
                    "UNWIND $rows AS row " +
                    "CREATE (f:FaqEntry {id: row.id, question: row.question, answer: row.answer, customer_id: row.customer_id})",
                    Map.of("rows", entries)
            );

            // Ensure fulltext index exists
            try {
                session.run(
                        "CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]"
                );
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("EquivalentSchemaRuleAlreadyExists") || msg.toLowerCase().contains("equivalent index already exists"))) {
                    log.debug("Fulltext index already exists — skipping");
                } else {
                    log.warn("Could not create fulltext index: {}", msg);
                }
            }

            log.info("Indexed {} FAQ entries in Neo4j for customer {}", entries.size(), customerId);
            return entries.size();
        } catch (Exception e) {
            log.error("Failed to index FAQ entries in Neo4j for customer {}: {}", customerId, e.getMessage());
            return 0;
        }
    }

    /**
     * Parse FAQ entries from markdown text, injecting customerId.
     */
    private List<Map<String, Object>> parseFaqEntriesWithCustomer(String text, String customerId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Matcher matcher = QA_PATTERN.matcher("\n" + text);
        int idx = 1;
        while (matcher.find()) {
            String question = matcher.group(1).replaceAll("\\s+", " ").trim();
            String answer = matcher.group(2).replaceAll("\\s+", " ").trim();
            entries.add(Map.of(
                    "id", idx,
                    "question", question,
                    "answer", answer,
                    "customer_id", customerId
            ));
            idx++;
        }
        return entries;
    }
}
