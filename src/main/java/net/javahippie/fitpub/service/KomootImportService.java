package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootActivitySummaryDTO;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Fetches a temporary preview of completed Komoot activities for an authenticated FitPub user.
 *
 * <p>Komoot does not expose a public API for this use case. This service currently talks to the
 * same web API endpoints used by the Komoot website and therefore depends on their current
 * behavior.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KomootImportService {

    private static final int PAGE_SIZE = 100;
    private static final String KOMOOT_LANGUAGE = "en";

    private final RestTemplate restTemplate;

    @Value("${fitpub.komoot.base-url:https://www.komoot.com}")
    private String komootBaseUrl;

    public KomootActivitiesResponse fetchCompletedActivities(KomootImportRequest request) {
        List<KomootActivitySummaryDTO> activities = new ArrayList<>();

        URI nextUri = buildInitialUri(request.userId());
        HttpEntity<Void> httpEntity = new HttpEntity<>(buildHeaders(request.email(), request.password()));

        try {
            while (nextUri != null) {
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        nextUri, HttpMethod.GET, httpEntity, JsonNode.class);

                JsonNode root = response.getBody();
                if (root == null) {
                    throw new IllegalStateException("Komoot returned an empty response body.");
                }
                extractActivities(root, activities);
                nextUri = extractNextUri(root);
            }
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new IllegalArgumentException("Komoot login failed. Check email, password and Komoot ID.", e);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Komoot user or activities endpoint not found for the given Komoot ID.", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Failed to reach Komoot. The remote service may be unavailable.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Komoot activity list.", e);
        }

        log.info("Fetched {} completed Komoot activities for user ID {}", activities.size(), request.userId());
        return new KomootActivitiesResponse(request.userId(), activities.size(), activities);
    }

    private URI buildInitialUri(String userId) {
        String normalizedBaseUrl = komootBaseUrl.endsWith("/") ? komootBaseUrl.substring(0, komootBaseUrl.length() - 1) : komootBaseUrl;
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl + "/api/v007/users/" + userId + "/tours/")
                .queryParam("type", "tour_recorded")
                .queryParam("status", "private")
                .queryParam("name", "")
                .queryParam("hl", KOMOOT_LANGUAGE)
                .queryParam("sort_field", "date")
                .queryParam("sort_direction", "desc")
                .queryParam("page", 0)
                .queryParam("limit", PAGE_SIZE)
                .build()
                .toUri();
    }

    private HttpHeaders buildHeaders(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("application/hal+json"), MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptLanguageAsLocales(List.of(java.util.Locale.ENGLISH));
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
        headers.set(HttpHeaders.AUTHORIZATION, basicAuth(email, password));
        return headers;
    }

    private String basicAuth(String email, String password) {
        String credentials = email + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private void extractActivities(JsonNode root, List<KomootActivitySummaryDTO> activities) {
        JsonNode tours = root.path("_embedded").path("tours");
        if (!tours.isArray()) {
            return;
        }

        for (JsonNode tour : tours) {
            activities.add(new KomootActivitySummaryDTO(
                    tour.path("id").asLong(),
                    nullableText(tour, "name"),
                    nullableText(tour, "sport"),
                    nullableText(tour, "status"),
                    nullableText(tour, "type"),
                    parseDate(tour.path("date").asText(null)),
                    nullableDouble(tour, "distance"),
                    nullableInteger(tour, "duration"),
                    nullableInteger(tour, "time_in_motion"),
                    nullableDouble(tour, "elevation_up")
            ));
        }
    }

    private URI extractNextUri(JsonNode root) {
        String nextHref = root.path("_links").path("next").path("href").asText(null);
        if (nextHref == null || nextHref.isBlank()) {
            return null;
        }

        if (nextHref.startsWith("http://") || nextHref.startsWith("https://")) {
            return URI.create(nextHref);
        }

        String normalizedBaseUrl = komootBaseUrl.endsWith("/") ? komootBaseUrl.substring(0, komootBaseUrl.length() - 1) : komootBaseUrl;
        String normalizedNextHref = nextHref.startsWith("/") ? nextHref : "/" + nextHref;
        return URI.create(normalizedBaseUrl + normalizedNextHref);
    }

    private OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
