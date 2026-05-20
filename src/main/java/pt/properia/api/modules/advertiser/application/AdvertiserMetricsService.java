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

    public record FunnelDto(int n, int contacted, int qualified, int proposal, int won, int lost) {}
    public record CohortDto(int today, int last7Days, int last30Days) {}
    public record FinancialDto(long pipelineValue, long proposalValue, long wonValue) {}
    public record SourceBreakdownItem(String source, int total) {}

    public record MetricsDto(
        int leadsTotal, int leadsFresh, int leadsLate,
        int visitsRequested, int visitsConfirmed,
        double visitConversionRate, double winRate,
        FunnelDto funnel, CohortDto cohort, FinancialDto financial,
        List<SourceBreakdownItem> sourceBreakdown
    ) {}

    public MetricsDto getMetrics(UUID advertiserId, String source) {
        var now = Instant.now();
        var d1 = now.minus(1, ChronoUnit.DAYS);
        var d7 = now.minus(7, ChronoUnit.DAYS);
        var d30 = now.minus(30, ChronoUnit.DAYS);

        // leads by stage, source, created_at, listing price
        var leadRows = jdbc.sql("""
                SELECT l.id, l.stage, l.source, l.created_at,
                       p.amount AS price_amount
                FROM properia.leads l
                LEFT JOIN properia.listing_pricing p ON p.listing_id = l.listing_id
                WHERE l.advertiser_id = :adv
                  AND (:source IS NULL OR l.source = :source)
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
                  AND (:source IS NULL OR l.source = :source)
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

        return new MetricsDto(
            total, 0, 0,
            (int) visitsRequested, (int) visitsConfirmed,
            visitConversionRate, winRate,
            new FunnelDto(nw, contacted, qualified, proposal, won, lost),
            new CohortDto(today, last7, last30),
            new FinancialDto(pipelineValue, proposalValue, wonValue),
            breakdown
        );
    }

    // ── Listing metrics ───────────────────────────────────────────────────────

    public record ListingMetricItem(String listingId, String title, String status,
                                    int leadsTotal, int leadsNew, int visitsConfirmed,
                                    long priceAmount, String currencyCode) {}

    public List<ListingMetricItem> getListingMetrics(UUID advertiserId) {
        return jdbc.sql("""
                SELECT li.id, li.title, li.status,
                       COUNT(DISTINCT l.id) AS leads_total,
                       COUNT(DISTINCT CASE WHEN l.stage = 'new' THEN l.id END) AS leads_new,
                       COUNT(DISTINCT CASE WHEN v.status = 'confirmed' THEN v.id END) AS visits_confirmed,
                       COALESCE(p.amount, 0) AS price_amount,
                       COALESCE(p.currency_code, 'EUR') AS currency_code
                FROM properia.listings li
                LEFT JOIN properia.leads l ON l.listing_id = li.id
                LEFT JOIN properia.visits v ON v.lead_id = l.id
                LEFT JOIN properia.listing_pricing p ON p.listing_id = li.id
                WHERE li.advertiser_id = :adv
                  AND li.status != 'archived'
                GROUP BY li.id, li.title, li.status, p.amount, p.currency_code
                ORDER BY leads_total DESC, li.created_at DESC
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new ListingMetricItem(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getInt("leads_total"),
                rs.getInt("leads_new"),
                rs.getInt("visits_confirmed"),
                rs.getLong("price_amount"),
                rs.getString("currency_code")
            ))
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
                       COUNT(DISTINCT CASE WHEN li.status = 'active' THEN li.id END) AS listings_active,
                       COUNT(DISTINCT v.id) AS visits_total,
                       COUNT(DISTINCT CASE WHEN v.status = 'confirmed' THEN v.id END) AS visits_confirmed
                FROM properia.advertiser_users au
                JOIN properia.app_users u ON u.id = au.user_id
                LEFT JOIN properia.leads l ON l.advertiser_id = :adv AND l.assigned_to_user_id = u.id
                LEFT JOIN properia.listings li ON li.advertiser_id = :adv AND li.agent_user_id = u.id
                LEFT JOIN properia.visits v ON v.advertiser_id = :adv AND v.assigned_to_user_id = u.id
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

    // ── Pulse (top 8 automation tasks) ────────────────────────────────────────

    public record PulseItem(String id, String leadId, String listingId, String title,
                            String description, String actionLabel, String priority,
                            String createdAt, String href) {}

    public record PulseDto(List<PulseItem> items) {}

    public PulseDto getPulse(UUID advertiserId) {
        var followUpHours = 48;
        var proposalHours = 72;

        var rows = jdbc.sql("""
                SELECT l.id AS lead_id, l.listing_id, l.stage, l.created_at,
                       li.title AS listing_title,
                       (l.metadata::jsonb ->> 'contactName') AS contact_name
                FROM properia.leads l
                JOIN properia.listings li ON li.id = l.listing_id
                WHERE l.advertiser_id = :adv
                  AND l.stage NOT IN ('won','lost')
                ORDER BY l.created_at
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> new Object[]{
                rs.getString("lead_id"),
                rs.getString("listing_id"),
                rs.getString("stage"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("listing_title"),
                rs.getString("contact_name")
            })
            .list();

        var now = Instant.now();
        var items = new ArrayList<PulseItem>();
        for (var row : rows) {
            var stage = (String) row[2];
            var createdAt = (Instant) row[3];
            double ageHours = (double) ChronoUnit.MINUTES.between(createdAt, now) / 60.0;
            int threshold = "proposal".equals(stage) ? proposalHours : followUpHours;
            if (ageHours < threshold) continue;

            boolean isProposal = "proposal".equals(stage);
            var priority = isProposal || ageHours >= threshold * 2 ? "high" : "medium";
            var contactName = row[5] != null ? (String) row[5] : "Lead sem nome";
            var title = isProposal ? "Fazer follow-up da proposta" : "Responder ao lead em atraso";
            var description = contactName + " em " + row[4] + ". Último ponto conhecido: " + stage + ".";
            var actionLabel = isProposal ? "Retomar proposta" : "Responder agora";

            items.add(new PulseItem(
                "automation-" + row[0],
                (String) row[0], (String) row[1],
                title, description, actionLabel, priority,
                createdAt.toString(), "/anunciante/leads"
            ));
            if (items.size() >= 8) break;
        }
        return new PulseDto(items);
    }
}
