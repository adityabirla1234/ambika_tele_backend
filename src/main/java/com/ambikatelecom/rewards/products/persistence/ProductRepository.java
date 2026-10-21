package com.ambikatelecom.rewards.products.persistence;

import com.ambikatelecom.rewards.products.domain.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByActiveTrueOrderByBrandAscModelAsc();

    Optional<Product> findBySku(String sku);

    @Query("select p from Product p where p.active in :actives and lower(p.category) like :category "
            + "and lower(p.brand) like :brand and (lower(p.brand) like :q or lower(p.model) like :q or lower(p.sku) like :q)")
    Page<Product> search(@Param("actives") Collection<Boolean> actives, @Param("category") String category,
                         @Param("brand") String brand, @Param("q") String q, Pageable pageable);

    @Query("select distinct p.category from Product p order by p.category")
    List<String> categories();

    @Query("select distinct p.brand from Product p order by p.brand")
    List<String> brands();
}
