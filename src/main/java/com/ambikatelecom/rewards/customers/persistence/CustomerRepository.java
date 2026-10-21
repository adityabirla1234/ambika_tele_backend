package com.ambikatelecom.rewards.customers.persistence;

import com.ambikatelecom.rewards.customers.domain.Customer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByPhoneE164(String phoneE164);

    /** Customers first registered in the half-open interval [start, end). */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant start, Instant end);

    /**
     * Keyset page for the monthly report, ordered by (created_at, id). Start with lastAt = start and lastId = 0,
     * then pass the last row of the previous page. Selects name and phone only.
     */
    @Query("select c.id as id, c.name as name, c.phoneE164 as phoneE164, c.createdAt as createdAt from Customer c "
            + "where c.createdAt >= :start and c.createdAt < :end "
            + "and (c.createdAt > :lastAt or (c.createdAt = :lastAt and c.id > :lastId)) "
            + "order by c.createdAt asc, c.id asc")
    List<CustomerReportRow> findReportPage(@Param("start") Instant start, @Param("end") Instant end,
                                           @Param("lastAt") Instant lastAt, @Param("lastId") Long lastId,
                                           Pageable page);
}
