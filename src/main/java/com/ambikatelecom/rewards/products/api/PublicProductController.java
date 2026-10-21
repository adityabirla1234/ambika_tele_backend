package com.ambikatelecom.rewards.products.api;

import com.ambikatelecom.rewards.products.domain.Product;
import com.ambikatelecom.rewards.products.persistence.ProductRepository;
import com.ambikatelecom.rewards.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicProductController {

    /** Customer-safe fields only. */
    public record ProductView(Long id, String category, String brand, String model) {}

    public record LookupRequest(@NotBlank(message = "Enter the IMEI or serial number.")
                                @Size(max = 40, message = "That number is too long.") String deviceIdentifier) {}

    private final ProductRepository products;

    public PublicProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping("/products")
    public List<ProductView> list() {
        return products.findByActiveTrueOrderByBrandAscModelAsc().stream()
                .map(p -> new ProductView(p.getId(), p.getCategory(), p.getBrand(), p.getModel()))
                .toList();
    }

    /**
     * The customer types the IMEI or serial number and gets the matching product. A product's SKU is the IMEI or serial
     * number of that exact device. The number travels in the body, never the URL, and only brand and model come back.
     */
    @PostMapping("/products/lookup")
    public ProductView lookup(@Valid @RequestBody LookupRequest body) {
        String raw = body.deviceIdentifier();
        String cleaned = raw.replaceAll("[\\s-]", "").toUpperCase();
        Product p = products.findBySku(cleaned)
                .or(() -> products.findBySku(raw.trim().toUpperCase()))
                .filter(Product::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "We could not find this device in our list. Check the number, or speak to our team at Ambika Telecom.",
                        "deviceIdentifier"));
        return new ProductView(p.getId(), p.getCategory(), p.getBrand(), p.getModel());
    }
}
