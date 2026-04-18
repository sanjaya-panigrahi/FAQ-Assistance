package com.mytechstore.faq.ingestion.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Customer entity
 * Provides data access operations for customer management
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Find customer by unique customer ID
     * @param customerId The unique identifier (e.g., "acme_corp")
     * @return Optional containing the customer if found
     */
    Optional<Customer> findByCustomerId(String customerId);

    /**
     * Find all active customers
     * @return List of active customers
     */
    List<Customer> findByIsActiveTrue();

    /**
     * Check if customer exists by ID
     * @param customerId The customer identifier
     * @return true if customer exists
     */
    boolean existsByCustomerId(String customerId);

}
