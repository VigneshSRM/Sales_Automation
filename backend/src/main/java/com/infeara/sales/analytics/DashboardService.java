package com.infeara.sales.analytics;

import com.infeara.sales.sales.dto.OpportunityDtos;
import com.infeara.sales.sales.pipeline.OpportunityService;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executive dashboard (FR-DASH-001..003).
 *
 * Every figure is computed server-side from authorised data and returned with its
 * definition so a number on screen can always be traced to a metric definition. Money
 * is never summed across currencies — each currency is reported separately (AMB-04).
 */
@Service
public class DashboardService {

    private final OpportunityService opportunities;
    private final ActivityFeedService activityFeed;
    private final CapabilityEvaluator capabilities;
    private final Clock clock;

    public DashboardService(OpportunityService opportunities, ActivityFeedService activityFeed,
                            CapabilityEvaluator capabilities, Clock clock) {
        this.opportunities = opportunities;
        this.activityFeed = activityFeed;
        this.capabilities = capabilities;
        this.clock = clock;
    }

    public record DashboardView(String periodStart, String periodEnd, String timezone,
                                OpportunityDtos.ForecastView forecast,
                                List<OpportunityDtos.CurrencyTotal> openPipeline,
                                List<OpportunityDtos.CurrencyTotal> weightedPipeline,
                                List<OpportunityDtos.CurrencyTotal> wonValue,
                                List<String> definitions, List<String> limitations,
                                String generatedAt) {
    }

    @Transactional(readOnly = true)
    public DashboardView dashboard(CurrentPrincipal principal, LocalDate from, LocalDate to) {
        capabilities.require(principal, "reports.read");
        LocalDate start = from == null ? LocalDate.now(clock).withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;
        OpportunityDtos.ForecastView forecast = opportunities.forecast(principal, start, end);
        List<String> limitations = List.of(
                "No FX conversion: pipeline and won values are reported per currency.",
                "CRM pipeline value is not recognised revenue.",
                "Email, meeting and AI health contribute once those modules are enabled for the workspace.");
        return new DashboardView(start.toString(), end.toString(), principal.timezone(), forecast,
                forecast.openByCurrency(), forecast.weightedByCurrency(), forecast.wonByCurrency(),
                forecast.definitions(), limitations, clock.instant().toString());
    }

    public ActivityFeedService feed() {
        return activityFeed;
    }

    public BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
