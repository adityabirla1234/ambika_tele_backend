package com.ambikatelecom.rewards.reporting.api;

import com.ambikatelecom.rewards.reporting.application.MonthlyReportService;
import com.ambikatelecom.rewards.reporting.application.MonthlyReportService.RunView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only run status for the manager screen (PRODUCT_MANAGER via SecurityConfig). Shows no customer data. */
@RestController
@RequestMapping("/api/product-manager/reports")
public class ReportRunController {

    private final MonthlyReportService service;

    public ReportRunController(MonthlyReportService service) {
        this.service = service;
    }

    @GetMapping("/runs")
    public List<RunView> runs() {
        return service.recentRuns();
    }
}
