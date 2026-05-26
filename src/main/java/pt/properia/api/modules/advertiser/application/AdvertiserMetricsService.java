package pt.properia.api.modules.advertiser.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AdvertiserMetricsService {

    private final JdbcClient jdbc;

    public AdvertiserMetricsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CohortDto(int today, int last7Days, int last30Days) {}
    public record FinancialDto(long pipelineValue, long proposalValue, long wonValue,
                               long averageProposalValue, double proposalToWinRate) {}
    public record SourceBreakdownItem(String source, int total) {}

    public record MetricsDto(
        int leadsTotal, int leadsFresh, int leadsLate, int leadsUnread,
        int visitsRequested, int visitsConfirmed,
        double visitConversionRate, double winRate,
        double responseRate, Integer avgFirstResponseMinutes,
        Map<String, Object> funnel, CohortDto cohort, FinancialDto financial,
        List<SourceBreakdownItem> sourceBreakdown,
        List<Object> closeReasons
    ) {}

    public MetricsDto getMetrics(UUID advertiserId, String source) {
        var now = Instant.now();
        var d1 = now.minus(1, ChronoUnit.DAYS);
        var d7 = now.minus(7, ChronoUnit.DAYS);
        var d30 = now.minus(30, ChronoUnit.DAYS);

        // leads by stage, source, created_at, listing price
        var leadRows = jdbc.sql("""
                SELECT l.id, l.stage, l.source, l.created_at,
                       COALESCE(p.list_price, 0) AS price_amount
                FROM properia.leads l
                LEFT JOIN properia.listing_pricing p ON p.listing_id = l.listing_id
                WHERE l.advertiser_id = :adv
                  AND (CAST(:source AS text) IS NULL OR l.source::text = :source)
                """)
            .param("adv", advertiserId)
            .param("source", (source != null && !source.equals("todas")) ? source : null)
            .query((rs, n) -> new Object[]{
                rs.getString("stage"),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("price_amount")
            })
            .list();

        // visits by status
        var visitStats = jdbc.sql("""
                SELECT v.status, COUNT(*) AS cnt
                FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE v.advertiser_id = :adv
                  AND (CAST(:source AS text) IS NULL OR l.source::text = :source)
                GROUP BY v.status
                """)
            .param("adv", advertiserId)
            .param("source", (source != null && !source.equals("todas")) ? source : null)
            .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("cnt")))
            .list();

        long visitsRequested = visitStats.stream()
            .filter(e -> "requested".equals(e.getKey())).mapToLong(Map.Entry::getValue).sum();
        long visitsConfirmed = visitStats.stream()
            .filter(e -> "confirmed".equals(e.getKey())).mapToLong(Map.Entry::getValue).sum();

        var stages = leadRows.stream().map(r -> (String) r[0]).toList();
        int total = stages.size();
        int won = (int) stages.stream().filter("won"::equals).count();
        int lost = (int) stages.stream().filter("lost"::equals).count();
        int proposal = (int) stages.stream().filter("proposal"::equals).count();
        int qualified = (int) stages.stream().filter("qualified"::equals).count();
        int contacted = (int) stages.stream()
            .filter(s -> "contacted".equals(s) || "visit_scheduled".equals(s)).count();
        int nw = (int) stages.stream().filter("new"::equals).count();

        // cohort
        int today = (int) leadRows.stream()
            .filter(r -> ((Instant) r[2]).isAfter(d1)).count();
        int last7 = (int) leadRows.stream()
            .filter(r -> ((Instant) r[2]).isAfter(d7)).count();
        int last30 = (int) leadRows.stream()
            .filter(r -> ((Instant) r[2]).isAfter(d30)).count();

        // financial — sum pipeline value from listing price (no metadata parsing needed for basic)
        long pipelineValue = leadRows.stream()
            .filter(r -> !"lost".equals(r[0]) && !"won".equals(r[0]))
            .mapToLong(r -> (Long) r[3]).sum();
        long proposalValue = leadRows.stream()
            .filter(r -> "proposal".equals(r[0]))
            .mapToLong(r -> (Long) r[3]).sum();
        long wonValue = leadRows.stream()
            .filter(r -> "won".equals(r[0]))
            .mapToLong(r -> (Long) r[3]).sum();

        // source breakdown
        var sourceMap = new LinkedHashMap<String, Integer>();
        for (var row : leadRows) {
            var s = (String) row[1];
            if (s != null) sourceMap.merge(s, 1, Integer::sum);
        }
        var breakdown = sourceMap.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> new SourceBreakdownItem(e.getKey(), e.getValue()))
            .toList();

        double visitConversionRate = total > 0 ? Math.round((double) (visitsRequested + visitsConfirmed) / total * 1000) / 10.0 : 0;
        double winRate = total > 0 ? Math.round((double) won / total * 1000) / 10.0 : 0;
        long avgProposalValue = proposal > 0 ? proposalValue / proposal : 0;
        double proposalToWinRate = proposal > 0 ? Math.round((double) won / proposal * 1000) / 10.0 : 0;

        return new MetricsDto(
            total, today, (int) leadRows.stream().filter(r -> {
                var age = ChronoUnit.HOURS.between((Instant) r[2], Instant.now());
                return age >= 72 && !"won".equals(r[0]) && !"lost".equals(r[0]);
            }).count(),
            0,
            (int) visitsRequested, (int) visitsConfirmed,
            visitConversionRate, winRate,
            0.0, null,
            Map.of("new", nw, "contacted", contacted, "qualified", qualified,
                   "proposal", proposal, "won", won, "lost", lost),
            new CohortDto(today, last7, last30),
            new FinancialDto(pipelineValue, proposalValue, wonValue, avgProposalValue, proposalToWinRate),
            breakdown,
            List.of()
        );
    }

    // ── Listing metrics ───────────────────────────────────────────────────────

    public record ListingMetricItem(
        String listingId, String listingTitle, String publicId,
        String city, String district, String status,
        int detailViewsTotal, int leadsTotal,
        int visitsTotal, int visitsConfirmed,
        int wonTotal, double conversionRate,
        long priceAmount, String currencyCode,
        Map<String, Integer> funnel
    ) {}

    public List<ListingMetricItem> getListingMetrics(UUID advertiserId) {
        return jdbc.sql("""
                SELECT li.id, li.title, li.public_id, li.city, li.district, li.status,
                       COUNT(DISTINCT dv.id) AS detail_views_total,
                       COUNT(DISTINCT l.id) AS leads_total,
                       COUNT(DISTINCT v.id) AS visits_total,
                       COUNT(DISTINCT CASE WHEN v.status = 'confirmed' THEN v.id END) AS visits_confirmed,
                       COUNT(DISTINCT CASE WHEN l.stage::text = 'won' THEN l.id END) AS won_total,
                       COUNT(DISTINCT CASE WHEN l.stage::text = 'new' THEN l.id END) AS leads_new,
                       COUNT(DISTINCT CASE WHEN l.stage::text IN ('contacted','visit_scheduled') THEN l.id END) AS leads_contacted,
                       COUNT(DISTINCT CASE WHEN l.stage::text = 'qualified' THEN l.id END) AS leads_qualified,
                       COUNT(DISTINCT CASE WHEN l.stage::text = 'proposal' THEN l.id END) AS leads_proposal,
                       COUNT(DISTINCT CASE WHEN l.stage::text = 'lost' THEN l.id END) AS leads_lost,
                       COALESCE(p.list_price, 0) AS price_amount,
                       COALESCE(p.price_currency, 'EUR') AS currency_code
                FROM properia.listings li
                LEFT JOIN properia.leads l ON l.listing_id = li.id
                LEFT JOIN properia.visits v ON v.lead_id = l.id
                LEFT JOIN properia.listing_pricing p ON p.listing_id = li.id
                LEFT JOIN properia.listing_detail_views dv ON dv.listing_id = li.id
                WHERE li.advertiser_id = :adv
                  AND li.status::text != 'archived'
                GROUP BY li.id, li.title, li.public_id, li.city, li.district, li.status, p.list_price, p.price_currency
                ORDER BY leads_total DESC, li.created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                int leadsTotal = rs.getInt("leads_total");
                int visitsTotal = rs.getInt("visits_total");
                double conversionRate = leadsTotal > 0
                    ? Math.round((double) visitsTotal / leadsTotal * 1000) / 1000.0 : 0.0;
                return new ListingMetricItem(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("public_id"),
                    rs.getString("city"),
                    rs.getString("district"),
                    rs.getString("status"),
                    rs.getInt("detail_views_total"),
                    leadsTotal,
                    visitsTotal,
                    rs.getInt("visits_confirmed"),
                    rs.getInt("won_total"),
                    conversionRate,
                    rs.getLong("price_amount"),
                    rs.getString("currency_code"),
                    Map.of(
                        "new", rs.getInt("leads_new"),
                        "contacted", rs.getInt("leads_contacted"),
                        "qualified", rs.getInt("leads_qualified"),
                        "proposal", rs.getInt("leads_proposal"),
                        "won", rs.getInt("won_total"),
                        "lost", rs.getInt("leads_lost")
                    )
                );
            })
            .list();
    }

    // ── Agent metrics ─────────────────────────────────────────────────────────

    public record AgentMetricItem(String userId, String name, String avatarUrl,
                                  String membershipRole, int leadsTotal, int leadsWon,
                                  int listingsTotal, int listingsActive,
                                  int visitsTotal, int visitsConfirmed) {}

    public List<AgentMetricItem> getAgentMetrics(UUID advertiserId) {
        return jdbc.sql("""
                SELECT u.id AS user_id, u.full_name, u.avatar_url, au.membership_role,
                       COUNT(DISTINCT l.id) AS leads_total,
                       COUNT(DISTINCT CASE WHEN l.stage = 'won' THEN l.id END) AS leads_won,
                       COUNT(DISTINCT li.id) AS listings_total,
                       COUNT(DISTINCT CASE WHEN li.status = 'published' THEN li.id END) AS listings_active,
                       COUNT(DISTINCT v.id) AS visits_total,
                       COUNT(DISTINCT CASE WHEN v.status = 'confirmed' THEN v.id END) AS visits_confirmed
                FROM properia.advertiser_users au
                JOIN properia.app_users u ON u.id = au.user_id
                LEFT JOIN properia.leads l ON l.advertiser_id = :adv AND l.assigned_to = u.id
                LEFT JOIN properia.listings li ON li.advertiser_id = :adv AND li.owner_user_id = u.id
                LEFT JOIN properia.visits v ON v.lead_id = l.id
                WHERE au.advertiser_id = :adv
                GROUP BY u.id, u.full_name, u.avatar_url, au.membership_role
                ORDER BY leads_total DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new AgentMetricItem(
                rs.getString("user_id"),
                rs.getString("full_name"),
                rs.getString("avatar_url"),
                rs.getString("membership_role"),
                rs.getInt("leads_total"),
                rs.getInt("leads_won"),
                rs.getInt("listings_total"),
                rs.getInt("listings_active"),
                rs.getInt("visits_total"),
                rs.getInt("visits_confirmed")
            ))
            .list();
    }

    // ── Pulse ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getPulse(UUID advertiserId) {
        var now = Instant.now();
        var ts7dAgo  = java.sql.Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        var ts30dAgo = java.sql.Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        var ts48hAgo = java.sql.Timestamp.from(now.minus(48, ChronoUnit.HOURS));
        var ts72hAgo = java.sql.Timestamp.from(now.minus(72, ChronoUnit.HOURS));

        // Week label (ISO week / year)
        var today = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Lisbon"));
        var week = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        var weekYear = today.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
        var weekLabel = week + "/" + weekYear;

        // Funnel: count active leads per stage
        var funnelMap = new LinkedHashMap<String, Object>();
        funnelMap.put("new", 0); funnelMap.put("contacted", 0);
        funnelMap.put("qualified", 0); funnelMap.put("proposal", 0);
        funnelMap.put("won", 0); funnelMap.put("lost", 0);
        jdbc.sql("""
                SELECT stage::text, COUNT(*) AS cnt FROM properia.leads
                WHERE advertiser_id = :adv
                GROUP BY stage
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> Map.entry(rs.getString("stage"), (int) rs.getLong("cnt")))
            .list()
            .forEach(e -> funnelMap.put(e.getKey(), e.getValue()));

        int pipelineLeads = ((Integer) funnelMap.getOrDefault("qualified", 0))
            + ((Integer) funnelMap.getOrDefault("proposal", 0));

        // New leads in last 7 days
        var newLeads = jdbc.sql("""
                SELECT COUNT(*) FROM properia.leads
                WHERE advertiser_id = :adv AND created_at > :since
                """)
            .param("adv", advertiserId).param("since", ts7dAgo)
            .query(Long.class).single().intValue();

        // Response rate: leads in last 30 days that moved past 'new' / total
        long leadsLast30 = jdbc.sql("""
                SELECT COUNT(*) FROM properia.leads
                WHERE advertiser_id = :adv AND created_at > :since
                """)
            .param("adv", advertiserId).param("since", ts30dAgo)
            .query(Long.class).single();
        long respondedLast30 = jdbc.sql("""
                SELECT COUNT(*) FROM properia.leads
                WHERE advertiser_id = :adv AND created_at > :since
                  AND stage::text NOT IN ('new','lost')
                """)
            .param("adv", advertiserId).param("since", ts30dAgo)
            .query(Long.class).single();
        double responseRate = leadsLast30 > 0 ? (double) respondedLast30 / leadsLast30 : 0.0;

        // Visits (use lead join same as getMetrics)
        var visitStats = jdbc.sql("""
                SELECT v.status::text, COUNT(*) AS cnt
                FROM properia.visits v
                JOIN properia.leads l ON l.id = v.lead_id
                WHERE v.advertiser_id = :adv AND l.created_at > :since
                GROUP BY v.status
                """)
            .param("adv", advertiserId).param("since", ts30dAgo)
            .query((rs, n) -> Map.entry(rs.getString("status"), (int) rs.getLong("cnt")))
            .list();
        var visitsRequested = visitStats.stream()
            .filter(e -> "requested".equals(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        var visitsConfirmed = visitStats.stream()
            .filter(e -> "confirmed".equals(e.getKey())).mapToInt(Map.Entry::getValue).sum();

        // At-risk leads (stalled > 48h in non-proposal, > 72h in proposal)
        var atRiskRows = jdbc.sql("""
                SELECT l.id::text, l.stage::text, l.contact_name,
                       l.updated_at, li.title AS listing_title
                FROM properia.leads l
                JOIN properia.listings li ON li.id = l.listing_id
                WHERE l.advertiser_id = :adv
                  AND l.stage::text NOT IN ('won','lost')
                  AND (
                    (l.stage::text = 'proposal' AND l.updated_at < :proposalCutoff)
                    OR (l.stage::text != 'proposal' AND l.updated_at < :slaCutoff)
                  )
                ORDER BY l.updated_at ASC
                LIMIT 10
                """)
            .param("adv", advertiserId)
            .param("slaCutoff", ts48hAgo)
            .param("proposalCutoff", ts72hAgo)
            .query((rs, n) -> {
                var ts = rs.getTimestamp("updated_at");
                long days = ts != null ? ChronoUnit.DAYS.between(ts.toInstant(), now) : 0;
                var stage = rs.getString("stage");
                var row = new LinkedHashMap<String, Object>();
                row.put("id", rs.getString("id"));
                row.put("contactName", rs.getString("contact_name"));
                row.put("listingTitle", Objects.requireNonNullElse(rs.getString("listing_title"), "Imóvel"));
                row.put("stage", stage);
                row.put("daysSinceActivity", (int) Math.max(days, 1));
                row.put("reason", "proposal".equals(stage) ? "stalled_proposal" : "sla_breached");
                return row;
            })
            .list();

        int atRiskCount = atRiskRows.size();

        // Health score
        String healthScore;
        if (atRiskCount == 0 && responseRate >= 0.6) {
            healthScore = "good";
        } else if (atRiskCount <= 2 || responseRate >= 0.3) {
            healthScore = "attention";
        } else {
            healthScore = "risk";
        }

        // Forecast
        var forecast = new LinkedHashMap<String, Object>();
        forecast.put("pipelineLeads", pipelineLeads);
        forecast.put("closingsMin", (int) Math.floor(pipelineLeads * 0.2));
        forecast.put("closingsMax", (int) Math.ceil(pipelineLeads * 0.4));

        // Insight (static based on health)
        var insight = new LinkedHashMap<String, Object>();
        if ("good".equals(healthScore)) {
            insight.put("headline", "Operação em boa forma esta semana");
            insight.put("detail", "A carteira está equilibrada. Mantém o ritmo de resposta para preservar a taxa de conversão.");
        } else if ("attention".equals(healthScore)) {
            insight.put("headline", atRiskCount + " contacto" + (atRiskCount == 1 ? "" : "s") + " precisa" + (atRiskCount == 1 ? "" : "m") + " de seguimento");
            insight.put("detail", "Alguns leads estão a ficar sem resposta. Prioriza os mais antigos para não perder oportunidades.");
        } else {
            insight.put("headline", "Vários contactos em risco de fuga");
            insight.put("detail", "A taxa de resposta está baixa e há leads sem seguimento. Actua hoje para recuperar o ritmo comercial.");
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("weekLabel", weekLabel);
        result.put("healthScore", healthScore);
        result.put("newLeads", newLeads);
        result.put("responseRate", Math.round(responseRate * 100.0) / 100.0);
        result.put("visitsConfirmed", visitsConfirmed);
        result.put("visitsRequested", visitsRequested);
        result.put("avgResponseMinutes", null);
        result.put("atRiskCount", atRiskCount);
        result.put("funnel", funnelMap);
        result.put("forecast", forecast);
        result.put("atRiskLeads", atRiskRows);
        result.put("insight", insight);
        result.put("isPro", true);
        result.put("generatedAt", now.toString());
        return result;
    }
}
