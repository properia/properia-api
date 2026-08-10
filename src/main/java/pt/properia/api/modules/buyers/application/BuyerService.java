package pt.properia.api.modules.buyers.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.properia.api.modules.buyers.domain.BuyerProfile;
import pt.properia.api.modules.buyers.infrastructure.BuyerProfileJpaRepository;
import pt.properia.api.shared.domain.DomainException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class BuyerService {

    private final BuyerProfileJpaRepository repo;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public BuyerService(BuyerProfileJpaRepository repo,
                        org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public record BuyerListResult(List<BuyerProfile> items, long total, int page, int pageSize, int totalPages) {}

    // Limiares dos budget_bracket (euros), ordem ascendente — mesmo mapeamento do BUDGET_LABEL
    // no frontend (buyer-list-page.tsx). Usado pelo filtro "orçamento mínimo": um bracket
    // qualifica-se se o seu limite inferior for >= ao valor pedido.
    private static final List<Map.Entry<String, Integer>> BUDGET_BRACKETS_ASC = List.of(
        Map.entry("under_100k", 0), Map.entry("100_150k", 100_000), Map.entry("150_200k", 150_000),
        Map.entry("200_250k", 200_000), Map.entry("250_300k", 250_000), Map.entry("300_400k", 300_000),
        Map.entry("400_500k", 400_000), Map.entry("500_750k", 500_000), Map.entry("750k_1m", 750_000),
        Map.entry("over_1m", 1_000_000)
    );

    @Transactional(readOnly = true)
    public BuyerListResult listProfiles(UUID advertiserId, String status, UUID assignedToUserId,
                                        String q, int page, int pageSize,
                                        Boolean hasUnsentMatches, String consentStatus,
                                        Integer minBudget, String propertyType) {
        var eligibleIds = computeEligibleIds(advertiserId, hasUnsentMatches, consentStatus, minBudget, propertyType);
        if (eligibleIds != null && eligibleIds.isEmpty()) {
            return new BuyerListResult(List.of(), 0, page, pageSize, 0);
        }
        var pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = repo.search(advertiserId, status, assignedToUserId, q, eligibleIds, pageable);
        var items = result.getContent();
        applyMatchCounts(advertiserId, items);
        applyTopMatchPreviews(advertiserId, items);
        return new BuyerListResult(
            items, result.getTotalElements(), page, pageSize, result.getTotalPages()
        );
    }

    /**
     * Filtros avançados (badge "Com imóveis compatíveis sem envio", consentimento, orçamento
     * mínimo, tipo de imóvel) — expressos em SQL puro porque envolvem jsonb (criteria->
     * propertyTypes) e EXISTS a buyer_listing_matches, que o JPQL do repositório não modela
     * bem. Devolve null = sem restrição (não filtra); Set vazio = nenhum comprador qualifica.
     */
    private java.util.Set<UUID> computeEligibleIds(UUID advertiserId, Boolean hasUnsentMatches,
                                                    String consentStatus, Integer minBudget, String propertyType) {
        if (hasUnsentMatches == null && consentStatus == null && minBudget == null && propertyType == null) {
            return null;
        }
        var whereParts = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        whereParts.add("bp.advertiser_id = :adv");
        params.put("adv", advertiserId);

        if (Boolean.TRUE.equals(hasUnsentMatches)) {
            whereParts.add("""
                    EXISTS (
                      SELECT 1 FROM properia.buyer_listing_matches m
                      WHERE m.buyer_profile_id = bp.id AND m.status::text = 'new'
                    )
                    """);
        }
        if (consentStatus != null && !consentStatus.isBlank()) {
            whereParts.add("bp.consent_status::text = :consentStatus");
            params.put("consentStatus", consentStatus);
        }
        if (minBudget != null) {
            var brackets = BUDGET_BRACKETS_ASC.stream()
                .filter(e -> e.getValue() >= minBudget)
                .map(Map.Entry::getKey)
                .toList();
            if (brackets.isEmpty()) return java.util.Set.of();
            whereParts.add("bp.budget_bracket::text IN (:brackets)");
            params.put("brackets", brackets);
        }
        if (propertyType != null && !propertyType.isBlank()) {
            whereParts.add("bp.criteria->'propertyTypes' @> to_jsonb(:propertyType::text)");
            params.put("propertyType", propertyType);
        }

        var sql = "SELECT bp.id FROM properia.buyer_profiles bp WHERE " + String.join(" AND ", whereParts);
        var query = jdbc.sql(sql);
        for (var e : params.entrySet()) query = query.param(e.getKey(), e.getValue());
        return new java.util.HashSet<>(query.query(UUID.class).list());
    }

    private void applyMatchCounts(UUID advertiserId, List<BuyerProfile> items) {
        if (items.isEmpty()) return;
        var counts = new java.util.HashMap<UUID, Integer>();
        jdbc.sql("SELECT buyer_profile_id, COUNT(*) AS c FROM properia.buyer_listing_matches WHERE advertiser_id = :adv GROUP BY buyer_profile_id")
            .param("adv", advertiserId)
            .query((rs, n) -> counts.put(rs.getObject("buyer_profile_id", UUID.class), rs.getInt("c")))
            .list();
        for (var item : items) {
            item.setMatchCount(counts.getOrDefault(item.getId(), 0));
        }
    }

    /**
     * Top 3 fotos por comprador para a mini-barra de avatares sobrepostos no cartão da
     * listagem — uma única query com window function para a página inteira (evita N+1;
     * o mesmo padrão de "buscar em lote depois do SELECT principal" já usado em
     * LeadController.listForAdvertiser para conversation/responses).
     */
    private void applyTopMatchPreviews(UUID advertiserId, List<BuyerProfile> items) {
        if (items.isEmpty()) return;
        var ids = items.stream().map(BuyerProfile::getId).toList();
        var byBuyer = new java.util.HashMap<UUID, List<Map<String, Object>>>();
        jdbc.sql("""
                SELECT buyer_profile_id, listing_id, hero_image_url, match_score
                FROM (
                  SELECT m.buyer_profile_id, m.listing_id, l.hero_image_url, m.match_score,
                         ROW_NUMBER() OVER (PARTITION BY m.buyer_profile_id ORDER BY m.match_score DESC) AS rn
                  FROM properia.buyer_listing_matches m
                  JOIN properia.listings l ON l.id = m.listing_id
                  WHERE m.advertiser_id = :adv AND m.buyer_profile_id IN (:ids)
                ) ranked
                WHERE rn <= 3
                ORDER BY match_score DESC
                """)
            .param("adv", advertiserId)
            .param("ids", ids)
            .query((rs, n) -> {
                var buyerId = rs.getObject("buyer_profile_id", UUID.class);
                var preview = new LinkedHashMap<String, Object>();
                preview.put("listingId", rs.getObject("listing_id", UUID.class));
                preview.put("coverImageUrl", rs.getString("hero_image_url"));
                preview.put("matchScore", rs.getInt("match_score"));
                byBuyer.computeIfAbsent(buyerId, k -> new ArrayList<>()).add(preview);
                return buyerId;
            })
            .list();
        for (var item : items) {
            item.setTopMatchPreviews(byBuyer.getOrDefault(item.getId(), List.of()));
        }
    }

    /** Marca matches como "notified" depois de o agente usar "Sugerir Imóveis" — fecha o loop do filtro "sem envio". */
    public void notifyMatches(UUID advertiserId, UUID buyerProfileId, List<UUID> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) return;
        jdbc.sql("""
                UPDATE properia.buyer_listing_matches
                SET status = 'notified', updated_at = now()
                WHERE advertiser_id = :adv AND buyer_profile_id = :bp AND listing_id IN (:listingIds)
                """)
            .param("adv", advertiserId)
            .param("bp", buyerProfileId)
            .param("listingIds", listingIds)
            .update();
    }

    public BuyerProfile getProfile(UUID advertiserId, UUID id, UUID assignedToUserId) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        // Recalcula os imóveis compatíveis (apenas do próprio anunciante) e devolve-os.
        syncMatches(advertiserId, profile);
        var matches = loadMatches(advertiserId, id);
        profile.setMatches(matches);
        profile.setMatchCount(matches.size());
        return profile;
    }

    public BuyerProfile createProfile(UUID advertiserId, UUID assignedToUserId, Map<String, Object> input) {
        var profile = new BuyerProfile();
        profile.setAdvertiserId(advertiserId);
        profile.setAssignedToUserId(assignedToUserId);
        profile.setConsentToken(UUID.randomUUID());
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        applyInput(profile, input);
        var saved = repo.save(profile);
        syncMatches(advertiserId, saved);
        return saved;
    }

    public BuyerProfile updateProfile(UUID advertiserId, UUID id, UUID assignedToUserId, Map<String, Object> input) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        applyInput(profile, input);
        profile.setUpdatedAt(Instant.now());
        var saved = repo.save(profile);
        syncMatches(advertiserId, saved);
        return saved;
    }

    public void deleteProfile(UUID advertiserId, UUID id, UUID assignedToUserId) {
        var profile = repo.findByAdvertiserIdAndId(advertiserId, id)
            .orElseThrow(() -> new DomainException("NOT_FOUND", "Comprador não encontrado.", 404));

        if (assignedToUserId != null && !assignedToUserId.equals(profile.getAssignedToUserId())) {
            throw new DomainException("NOT_FOUND", "Comprador não encontrado.", 404);
        }
        repo.delete(profile);
    }

    @SuppressWarnings("unchecked")
    private void applyInput(BuyerProfile p, Map<String, Object> input) {
        if (input.containsKey("name")) p.setName((String) input.get("name"));
        if (input.containsKey("email")) p.setEmail((String) input.get("email"));
        if (input.containsKey("phone")) p.setPhone((String) input.get("phone"));
        if (input.containsKey("urgency")) p.setUrgency((String) input.get("urgency"));
        if (input.containsKey("budgetBracket")) p.setBudgetBracket((String) input.get("budgetBracket"));
        if (input.containsKey("budgetApproval")) p.setBudgetApproval((String) input.get("budgetApproval"));
        if (input.containsKey("situation")) p.setSituation((String) input.get("situation"));
        if (input.containsKey("status")) p.setStatus((String) input.get("status"));
        if (input.containsKey("closeReason")) p.setCloseReason((String) input.get("closeReason"));
        if (input.containsKey("internalNotes")) p.setInternalNotes((String) input.get("internalNotes"));
        if (input.containsKey("criteria")) p.setCriteria((Map<String, Object>) input.get("criteria"));
        if (input.containsKey("assignedToUserId") && input.get("assignedToUserId") != null) {
            p.setAssignedToUserId(UUID.fromString((String) input.get("assignedToUserId")));
        }
        if (input.containsKey("nextFollowUpAt") && input.get("nextFollowUpAt") != null) {
            p.setNextFollowUpAt(Instant.parse((String) input.get("nextFollowUpAt")));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Motor de matching: imóveis DO PRÓPRIO anunciante vs critérios do comprador.
    // RGPD: tenant isolation total — só lê listings com advertiser_id = :adv.
    // ──────────────────────────────────────────────────────────────────────

    private static final int MATCH_MIN_SCORE = 55;
    private static final int MATCH_MAX = 12;

    /**
     * Recalcula os matches de TODOS os compradores ativos da agência — chamado quando um
     * imóvel é publicado ou editado de forma que possa mudar a compatibilidade (preço,
     * localização, tipologia, área). Antes disto, um imóvel novo só aparecia como
     * compatível depois de alguém abrir manualmente o perfil de cada comprador; o contador
     * "imóveis compatíveis" podia ficar desatualizado indefinidamente.
     */
    public void syncMatchesForAdvertiser(UUID advertiserId) {
        var profiles = repo.findAllByAdvertiserIdAndStatus(advertiserId, "active");
        for (var profile : profiles) {
            syncMatches(advertiserId, profile);
        }
    }

    /** Recalcula e persiste os matches (upsert + remoção dos obsoletos), preservando o estado. */
    public void syncMatches(UUID advertiserId, BuyerProfile profile) {
        var range = budgetRange(profile.getBudgetBracket());
        var criteria = profile.getCriteria() != null ? profile.getCriteria() : Map.<String, Object>of();

        var listings = jdbc.sql("""
                SELECT id, price_amount, bedrooms, usable_area_m2,
                       lower(coalesce(city,'')) AS city, lower(coalesce(district,'')) AS district,
                       lower(coalesce(parish,'')) AS parish, lower(coalesce(neighborhood,'')) AS neighborhood,
                       property_type::text AS property_type
                FROM properia.listings
                WHERE advertiser_id = :adv AND status = 'published' AND business_type = 'sale'
                """)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", rs.getObject("id", UUID.class));
                m.put("price", rs.getBigDecimal("price_amount") != null ? rs.getBigDecimal("price_amount").doubleValue() : 0d);
                m.put("bedrooms", (Integer) rs.getObject("bedrooms"));
                var area = rs.getBigDecimal("usable_area_m2");
                m.put("area", area != null ? area.doubleValue() : null);
                m.put("loc", String.join(" ", rs.getString("city"), rs.getString("district"), rs.getString("parish"), rs.getString("neighborhood")));
                m.put("type", rs.getString("property_type"));
                return m;
            })
            .list();

        var kept = new ArrayList<UUID>();
        for (var l : listings) {
            var scored = score(l, range, criteria);
            int s = (int) scored.get("score");
            if (s < MATCH_MIN_SCORE) continue;
            UUID listingId = (UUID) l.get("id");
            String critJson;
            try {
                critJson = json.writeValueAsString(scored.get("criteria"));
            } catch (Exception e) {
                critJson = "[]";
            }
            jdbc.sql("""
                    INSERT INTO properia.buyer_listing_matches
                        (buyer_profile_id, listing_id, advertiser_id, match_score, matched_criteria, status)
                    VALUES (:p, :l, :adv, :score, cast(:crit AS jsonb), 'new')
                    ON CONFLICT (buyer_profile_id, listing_id)
                    DO UPDATE SET match_score = EXCLUDED.match_score,
                                  matched_criteria = EXCLUDED.matched_criteria,
                                  updated_at = now()
                    """)
                .param("p", profile.getId())
                .param("l", listingId)
                .param("adv", advertiserId)
                .param("score", s)
                .param("crit", critJson)
                .update();
            kept.add(listingId);
            if (kept.size() >= MATCH_MAX) break;
        }

        // Remove matches que deixaram de encaixar (sempre dentro do tenant).
        if (kept.isEmpty()) {
            jdbc.sql("DELETE FROM properia.buyer_listing_matches WHERE buyer_profile_id = :p AND advertiser_id = :adv")
                .param("p", profile.getId()).param("adv", advertiserId).update();
        } else {
            jdbc.sql("DELETE FROM properia.buyer_listing_matches WHERE buyer_profile_id = :p AND advertiser_id = :adv AND listing_id NOT IN (:kept)")
                .param("p", profile.getId()).param("adv", advertiserId).param("kept", kept).update();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> score(Map<String, Object> l, double[] range, Map<String, Object> criteria) {
        double weighted = 0, totalWeight = 0;
        var labels = new ArrayList<String>();

        // Orçamento (sinal principal — sempre que o comprador tem bracket definido).
        if (range != null) {
            double price = (double) l.get("price");
            double lo = range[0], hi = range[1];
            double sub;
            if (price > 0 && price <= hi && price >= lo * 0.7) sub = 1.0;
            else if (price > 0 && price <= hi * 1.10) sub = 0.55;
            else if (price > 0 && price < lo * 0.7) sub = 0.6;
            else sub = 0.0;
            weighted += sub * 40; totalWeight += 40;
            if (sub >= 0.6) labels.add("Orçamento");
        }

        // Zonas.
        var zones = asStringList(criteria.get("zones"));
        if (!zones.isEmpty()) {
            String loc = (String) l.get("loc");
            boolean hit = zones.stream().anyMatch(z -> !z.isBlank() && loc.contains(z.toLowerCase()));
            weighted += (hit ? 1.0 : 0.0) * 20; totalWeight += 20;
            if (hit) labels.add("Zona");
        }

        // Tipologia de imóvel (apartment, house, …).
        var types = asStringList(criteria.get("propertyTypes"));
        if (!types.isEmpty()) {
            String type = (String) l.get("type");
            boolean hit = types.stream().anyMatch(t -> t.equalsIgnoreCase(type));
            weighted += (hit ? 1.0 : 0.0) * 15; totalWeight += 15;
            if (hit) labels.add("Tipo de imóvel");
        }

        // Quartos.
        Integer minB = asInt(criteria.get("minBedrooms"));
        Integer maxB = asInt(criteria.get("maxBedrooms"));
        if (minB != null || maxB != null) {
            Integer bed = (Integer) l.get("bedrooms");
            double sub = 0.0;
            if (bed != null) {
                boolean okMin = minB == null || bed >= minB;
                boolean okMax = maxB == null || bed <= maxB;
                if (okMin && okMax) sub = 1.0;
                else if ((minB != null && bed == minB - 1) || (maxB != null && bed == maxB + 1)) sub = 0.5;
            }
            weighted += sub * 15; totalWeight += 15;
            if (sub >= 0.6) labels.add("Quartos");
        }

        // Área útil.
        Integer minA = asInt(criteria.get("minArea"));
        Integer maxA = asInt(criteria.get("maxArea"));
        if (minA != null || maxA != null) {
            Double area = (Double) l.get("area");
            double sub = 0.0;
            if (area != null) {
                boolean okMin = minA == null || area >= minA;
                boolean okMax = maxA == null || area <= maxA;
                if (okMin && okMax) sub = 1.0;
                else if ((minA != null && area >= minA * 0.85) || (maxA != null && area <= maxA * 1.15)) sub = 0.5;
            }
            weighted += sub * 10; totalWeight += 10;
            if (sub >= 0.6) labels.add("Área");
        }

        int score = totalWeight > 0 ? (int) Math.round(100.0 * weighted / totalWeight) : 0;
        var out = new LinkedHashMap<String, Object>();
        out.put("score", score);
        out.put("criteria", labels);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadMatches(UUID advertiserId, UUID profileId) {
        return jdbc.sql("""
                SELECT m.id, m.listing_id, m.match_score, m.matched_criteria::text AS matched_criteria,
                       m.status::text AS status, m.created_at,
                       l.public_id, l.title, l.price_amount, l.bedrooms, l.usable_area_m2,
                       coalesce(l.parish, l.neighborhood) AS area_label, l.city, l.hero_image_url
                FROM properia.buyer_listing_matches m
                JOIN properia.listings l ON l.id = m.listing_id
                WHERE m.buyer_profile_id = :p AND m.advertiser_id = :adv
                ORDER BY m.match_score DESC, m.created_at DESC
                """)
            .param("p", profileId)
            .param("adv", advertiserId)
            .query((rs, n) -> {
                Map<String, Object> listing = new LinkedHashMap<>();
                UUID listingId = rs.getObject("listing_id", UUID.class);
                int bedrooms = rs.getInt("bedrooms");
                listing.put("id", listingId);
                listing.put("title", rs.getString("title"));
                listing.put("slug", rs.getString("public_id"));
                listing.put("preco", rs.getBigDecimal("price_amount") != null ? rs.getBigDecimal("price_amount").intValue() : 0);
                listing.put("tipologia", rs.getObject("bedrooms") != null ? "T" + bedrooms : null);
                var area = rs.getBigDecimal("usable_area_m2");
                listing.put("areaUtil", area != null ? area.intValue() : null);
                listing.put("morada", buildMorada(rs.getString("area_label"), rs.getString("city")));
                listing.put("coverImageUrl", rs.getString("hero_image_url"));

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", rs.getObject("id", UUID.class));
                out.put("listingId", listingId);
                out.put("matchScore", rs.getInt("match_score"));
                out.put("matchedCriteria", parseStringList(rs.getString("matched_criteria")));
                out.put("status", rs.getString("status"));
                out.put("listing", listing);
                var ts = rs.getTimestamp("created_at");
                out.put("createdAt", ts != null ? ts.toInstant().toString() : null);
                return out;
            })
            .list();
    }

    private static String buildMorada(String areaLabel, String city) {
        var parts = new ArrayList<String>();
        if (areaLabel != null && !areaLabel.isBlank()) parts.add(areaLabel);
        if (city != null && !city.isBlank()) parts.add(city);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private List<String> parseStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return List.of();
        try {
            return json.readValue(jsonArray, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v instanceof List<?> list) {
            var out = new ArrayList<String>();
            for (var o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    /** under_100k → [0,100000]; over_1m → [1_000_000, +inf]; null se sem bracket. */
    private static double[] budgetRange(String bracket) {
        if (bracket == null) return null;
        return switch (bracket) {
            case "under_100k" -> new double[]{0, 100_000};
            case "100_150k"   -> new double[]{100_000, 150_000};
            case "150_200k"   -> new double[]{150_000, 200_000};
            case "200_250k"   -> new double[]{200_000, 250_000};
            case "250_300k"   -> new double[]{250_000, 300_000};
            case "300_400k"   -> new double[]{300_000, 400_000};
            case "400_500k"   -> new double[]{400_000, 500_000};
            case "500_750k"   -> new double[]{500_000, 750_000};
            case "750k_1m"    -> new double[]{750_000, 1_000_000};
            case "over_1m"    -> new double[]{1_000_000, Double.MAX_VALUE};
            default -> null;
        };
    }
}
