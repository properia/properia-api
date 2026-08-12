package pt.properia.api.modules.geocoding.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.properia.api.modules.geocoding.application.GeocodingResult;
import pt.properia.api.modules.geocoding.application.ListingGeocodingResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NominatimGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingService.class);

    private final GeocodingProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NominatimGeocodingService(GeocodingProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
            .build();
    }

    public Optional<GeocodingResult> geocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        try {
            var arr = nominatimSearch("?q=" + enc(query.strip())
                + "&format=jsonv2&limit=1&countrycodes=pt&addressdetails=1");
            if (arr.isEmpty() || !arr.get(0).isArray() || arr.get(0).isEmpty()) return Optional.empty();
            var first = arr.get(0).get(0);
            return Optional.of(new GeocodingResult(
                first.path("display_name").asText(query),
                first.path("lat").asDouble(),
                first.path("lon").asDouble()
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Returns up to {@code limit} autocomplete candidates for a free-text query. */
    public List<GeocodingResult> suggest(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        var capped = Math.max(1, Math.min(limit, 8));
        try {
            var arr = nominatimSearch("?q=" + enc(query.strip())
                + "&format=jsonv2&limit=" + capped + "&countrycodes=pt&addressdetails=1");
            if (arr.isEmpty() || !arr.get(0).isArray() || arr.get(0).isEmpty()) return List.of();
            var node = arr.get(0);
            var out = new ArrayList<GeocodingResult>();
            for (int i = 0; i < Math.min(node.size(), capped); i++) {
                var item = node.get(i);
                double lat = item.path("lat").asDouble();
                double lng = item.path("lon").asDouble();
                if (lat == 0 && lng == 0) continue;
                out.add(new GeocodingResult(item.path("display_name").asText(query), lat, lng));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<ListingGeocodingResult> geocodeListingAddress(
            String street, String streetNumber, String postalCode,
            String city, String parish, String district) {

        // Build combined street string (street + number)
        var streetFull = street != null && !street.isBlank() ? street.strip() : null;
        if (streetFull != null && streetNumber != null && !streetNumber.isBlank()) {
            streetFull = streetFull + " " + streetNumber.strip();
        }

        // A cascata está ordenada do dado MAIS específico para o menos
        // específico, e cada nível só corre se o anterior não deu nada.
        //
        // A ordem importa e já esteve errada: o fallback de texto livre corria
        // antes dos passos de freguesia e, como usava "concelho OU freguesia",
        // devolvia sempre o centroide do concelho e a freguesia nunca era
        // tentada. Para um imóvel em São Mamede de Infesta isso são ~6 km de
        // erro — pino na zona errada do mapa, da análise de zona e da pesquisa
        // por trajeto, sem nada que indique que está errado.

        // ── Nível RUA ────────────────────────────────────────────────────────
        var candidates = isPresent(streetFull)
            ? tryStructured(streetFull, postalCode, city, parish)
            : List.<ListingGeocodingResult>of();

        // Nominatim indexa mal os códigos postais portugueses; sem ele acerta mais.
        if (candidates.isEmpty() && isPresent(streetFull)) {
            candidates = tryStructured(streetFull, null, city, parish);
        }

        // ── Nível CÓDIGO POSTAL ──────────────────────────────────────────────
        // Mais preciso do que a freguesia, por isso vem antes.
        if (candidates.isEmpty() && isPresent(postalCode)) {
            candidates = tryStructured(null, postalCode, city, parish);
        }

        if (candidates.isEmpty() && isPresent(streetFull)) {
            candidates = tryFreeText(streetFull, postalCode, city, parish, district);
        }

        // ── Nível FREGUESIA ──────────────────────────────────────────────────
        // Mapeamento para Portugal: freguesia→city, concelho→county,
        // distrito→state. Não é intuitivo, mas é o que o Nominatim entende.
        if (candidates.isEmpty() && isPresent(parish)) {
            candidates = tryParishStructured(parish, city, district);
        }

        // Os nomes "União das Freguesias de A e B" não existem na pesquisa
        // estruturada do Nominatim (verificado), mas resolvem em texto livre —
        // e é assim que a maioria do inventário vem preenchida.
        if (candidates.isEmpty() && isPresent(parish)) {
            candidates = tryParishFreeText(parish, city, district);
        }

        // ── Nível CONCELHO ───────────────────────────────────────────────────
        // Último recurso. O ponto é o centroide do concelho e é isso que a
        // precisão vai dizer — nunca "freguesia".
        if (candidates.isEmpty() && isPresent(postalCode)) {
            candidates = tryStructured(null, postalCode, city, null);
        }

        if (candidates.isEmpty() && (isPresent(city) || isPresent(district))) {
            candidates = tryMunicipality(city, district);
        }

        return candidates;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<ListingGeocodingResult> tryStructured(String street, String postalCode,
                                                         String city, String parish) {
        try {
            var sb = new StringBuilder("?format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (street != null && !street.isBlank())         sb.append("&street=").append(enc(street));
            if (postalCode != null && !postalCode.isBlank()) sb.append("&postalcode=").append(enc(postalCode));
            if (city != null && !city.isBlank())             sb.append("&city=").append(enc(city));
            else if (parish != null && !parish.isBlank())    sb.append("&city=").append(enc(parish));

            var results = nominatimSearch(sb.toString());
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim structured found {} results for street={} pc={} city={}", results.get(0).size(), street, postalCode, city);
            return parseResults(results.get(0));
        } catch (Exception e) {
            log.debug("Nominatim structured error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Pesquisa estruturada ao nível da freguesia.
     *
     * `city=freguesia` + `county=concelho` é o mapeamento que o Nominatim usa
     * para Portugal — as freguesias estão indexadas como `city`, e o concelho
     * como `county`. Verificado: São Mamede de Infesta com county=Matosinhos
     * devolve 41.1912,-8.6106, contra 41.1807,-8.6822 do centroide do concelho.
     */
    private List<ListingGeocodingResult> tryParishStructured(String parish, String city, String district) {
        try {
            var sb = new StringBuilder("?format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            sb.append("&city=").append(enc(parish.strip()));
            if (isPresent(city))     sb.append("&county=").append(enc(city.strip()));
            if (isPresent(district)) sb.append("&state=").append(enc(district.strip()));

            var results = nominatimSearch(sb.toString());
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim parish structured found {} results for parish={} county={}",
                results.get(0).size(), parish, city);
            return keepAdministrativeNames(parseResults(results.get(0), "parish"), parish, city, district);
        } catch (Exception e) {
            log.debug("Nominatim parish structured error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Último recurso: centroide do concelho (ou do distrito).
     *
     * O resultado é assumidamente grosseiro — pode estar a vários quilómetros do
     * imóvel. O tecto de precisão "municipality" existe para que quem consome
     * saiba disso e possa apresentar o ponto como aproximado em vez de o mostrar
     * como se fosse a morada.
     */
    private List<ListingGeocodingResult> tryMunicipality(String city, String district) {
        try {
            var parts = new ArrayList<String>();
            if (isPresent(city))     parts.add(city.strip());
            if (isPresent(district)) parts.add(district.strip());
            parts.add("Portugal");

            var results = nominatimSearch("?q=" + enc(String.join(", ", parts))
                + "&format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            return parseResults(results.get(0), "municipality");
        } catch (Exception e) {
            log.debug("Nominatim municipality error: {}", e.getMessage());
            return List.of();
        }
    }

    /** Texto livre ao nível da freguesia — apanha os nomes "União das Freguesias de A e B". */
    private List<ListingGeocodingResult> tryParishFreeText(String parish, String city, String district) {
        try {
            var parts = new ArrayList<String>();
            parts.add(parish.strip());
            if (isPresent(city))     parts.add(city.strip());
            if (isPresent(district)) parts.add(district.strip());
            parts.add("Portugal");

            var query = String.join(", ", parts);
            var results = nominatimSearch("?q=" + enc(query)
                + "&format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim parish free-text found {} results for: {}", results.get(0).size(), query);
            return keepAdministrativeNames(parseResults(results.get(0), "parish"), parish, city, district);
        } catch (Exception e) {
            log.debug("Nominatim parish free-text error: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ListingGeocodingResult> tryFreeText(String street, String postalCode,
                                                       String city, String parish, String district) {
        try {
            var parts = new ArrayList<String>();
            if (street != null && !street.isBlank())         parts.add(street);
            if (postalCode != null && !postalCode.isBlank()) parts.add(postalCode);
            if (city != null && !city.isBlank())             parts.add(city);
            else if (parish != null && !parish.isBlank())    parts.add(parish);
            if (district != null && !district.isBlank())     parts.add(district);
            parts.add("Portugal");

            var query = String.join(", ", parts);
            var results = nominatimSearch("?q=" + enc(query)
                + "&format=jsonv2&limit=5&countrycodes=pt&addressdetails=1");
            if (results.isEmpty() || results.get(0).isEmpty()) return List.of();

            log.debug("Nominatim free-text found {} results for: {}", results.get(0).size(), query);
            return parseResults(results.get(0));
        } catch (Exception e) {
            log.debug("Nominatim free-text error: {}", e.getMessage());
            return List.of();
        }
    }

    // Returns a single-element list containing the parsed array node, or empty on HTTP error
    private List<JsonNode> nominatimSearch(String queryString) throws Exception {
        var url = props.getUrl() + queryString;
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", props.getUserAgent())
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        var arr = objectMapper.readTree(response.body());
        if (!arr.isArray()) return List.of();
        return List.of(arr);
    }

    private List<ListingGeocodingResult> parseResults(JsonNode arr) {
        return parseResults(arr, null);
    }

    /**
     * @param queryPrecision nível a que a consulta foi feita. Quando presente,
     *   é ELE que define a precisão do resultado, e não os campos que o
     *   Nominatim por acaso devolveu.
     *
     *   O motivo é que as duas coisas medem realidades diferentes: os campos da
     *   resposta descrevem o que o OpenStreetMap sabe sobre aquele ponto; a
     *   precisão tem de descrever o que NÓS sabemos sobre o imóvel. Se só temos
     *   a freguesia, o ponto é de nível freguesia mesmo que o OSM devolva um nó
     *   detalhado — e continua a ser de nível freguesia mesmo que o OSM
     *   classifique a freguesia como "city", que é o que faz em Portugal e que
     *   levava a etiqueta a cair para "municipality".
     */
    private List<ListingGeocodingResult> parseResults(JsonNode arr, String queryPrecision) {
        var results = new ArrayList<ListingGeocodingResult>();
        for (int i = 0; i < Math.min(arr.size(), 5); i++) {
            var item = arr.get(i);
            var r = toListingResult(item);
            if (r != null) results.add(applyQueryPrecision(r, queryPrecision));
        }
        return results;
    }

    /**
     * Mantém os nomes administrativos que o chamador indicou, em vez dos que o
     * Nominatim devolve.
     *
     * Em Portugal o OpenStreetMap indexa as freguesias como `city`. Numa
     * pesquisa ao nível da freguesia, a resposta vem com
     * `city = "São Mamede de Infesta"` — a FREGUESIA no campo do concelho. Como
     * o formulário aplica o candidato com `city: c.city ?? city`, isso
     * substituía "Matosinhos" pela freguesia e corrompia `listings.city`, que é
     * a coluna usada na pesquisa por concelho e no motor de avaliação
     * (JdbcMarketPriceRepository compara `lower(l.city)`).
     *
     * Quem pesquisou sabe o que pesquisou: a freguesia e o concelho de entrada
     * são a fonte de verdade a este nível. Só as coordenadas vêm do Nominatim.
     */
    private static List<ListingGeocodingResult> keepAdministrativeNames(
            List<ListingGeocodingResult> results, String parish, String city, String district) {

        var out = new ArrayList<ListingGeocodingResult>(results.size());
        for (var r : results) {
            out.add(new ListingGeocodingResult(
                r.latitude(), r.longitude(), r.precision(), r.confidence(), r.displayAddress(),
                isPresent(district) ? district.strip() : r.district(),
                isPresent(city)     ? city.strip()     : r.city(),
                isPresent(parish)   ? parish.strip()   : r.parish(),
                r.neighborhood(),
                // Sem morada exata não há rua nem código postal a declarar —
                // devolver os do centroide da freguesia daria uma morada falsa.
                null, null
            ));
        }
        return out;
    }

    /** Confiança por nível — declarar 0.95 num ponto que só se sabe estar dentro
     *  de uma freguesia seria mentir sobre o dado. */
    private static double confidenceFor(String precision) {
        return switch (precision) {
            case "exact"        -> 0.95;
            case "street"       -> 0.80;
            case "neighborhood" -> 0.60;
            case "parish"       -> 0.40;
            default             -> 0.20;
        };
    }

    private static ListingGeocodingResult applyQueryPrecision(ListingGeocodingResult r, String queryPrecision) {
        if (queryPrecision == null) return r;

        return new ListingGeocodingResult(
            r.latitude(), r.longitude(), queryPrecision, confidenceFor(queryPrecision),
            r.displayAddress(), r.district(), r.city(), r.parish(),
            r.neighborhood(), r.street(), r.postalCode()
        );
    }

    private ListingGeocodingResult toListingResult(JsonNode item) {
        try {
            double lat = item.path("lat").asDouble();
            double lng = item.path("lon").asDouble();
            if (lat == 0 && lng == 0) return null;

            var addr = item.path("address");
            var displayName = item.path("display_name").asText(null);
            var addressType = item.path("addresstype").asText("");
            var type = item.path("type").asText("");

            // Extract address components from Nominatim response
            var road       = coalesce(addr, "road", "pedestrian", "footway", "path");
            var houseNum   = text(addr, "house_number");
            var suburb     = coalesce(addr, "suburb", "neighbourhood", "quarter");
            var cityVal    = coalesce(addr, "city", "town", "municipality", "county");
            var parishVal  = coalesce(addr, "city_district", "borough", "village", "hamlet");
            var districtVal = coalesce(addr, "state", "state_district", "county");
            var postcode   = text(addr, "postcode");

            // Build street with house number if present
            String streetOut = null;
            if (road != null) {
                streetOut = houseNum != null ? road + " " + houseNum : road;
            }

            // Determine precision level
            String precision;
            double confidence;
            if ("house".equals(type) || "house".equals(addressType)) {
                precision = "exact"; confidence = 0.95;
            } else if (road != null && postcode != null) {
                precision = "street"; confidence = 0.80;
            } else if (postcode != null) {
                precision = "neighborhood"; confidence = 0.60;
            } else if (parishVal != null) {
                // Só é precisão de freguesia se o Nominatim tiver mesmo devolvido
                // uma freguesia. Antes bastava existir `cityVal`, o que etiquetava
                // o centroide de um CONCELHO como precisão de freguesia — uma
                // promessa de rigor que o ponto não tinha.
                precision = "parish"; confidence = 0.40;
            } else {
                precision = "municipality"; confidence = 0.20;
            }

            return new ListingGeocodingResult(
                lat, lng,
                precision, confidence,
                displayName,
                districtVal,
                cityVal,
                parishVal,
                suburb,
                streetOut,
                postcode
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode addr, String field) {
        var v = addr.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    private static String coalesce(JsonNode addr, String... fields) {
        for (var f : fields) {
            var v = text(addr, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
