package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootActivitySummaryDTO;
import net.javahippie.fitpub.model.dto.KomootImportExecutionResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.util.ByteArrayMultipartFile;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private static final DateTimeFormatter KOMOOT_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");
    private final RestTemplate restTemplate;
    private final ActivityRepository activityRepository;
    private final ActivityFileService activityFileService;
    private final ActivityPostProcessingService activityPostProcessingService;

    @Value("${fitpub.komoot.base-url:https://www.komoot.com}")
    private String komootBaseUrl;

    public KomootActivitiesResponse fetchCompletedActivities(KomootImportRequest request, UUID fitPubUserId) {
        List<KomootActivitySummaryDTO> activities = new ArrayList<>();
        Set<Long> importedKomootActivityIds = new HashSet<>(
                activityRepository.findImportedKomootActivityIdsByUserId(fitPubUserId));

        URI nextUri = buildInitialUri(request);
        HttpEntity<Void> httpEntity = new HttpEntity<>(buildHeaders(request.email(), request.password()));

        try {
            while (nextUri != null) {
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        nextUri, HttpMethod.GET, httpEntity, JsonNode.class);

                JsonNode root = response.getBody();
                if (root == null) {
                    throw new IllegalStateException("Komoot returned an empty response body.");
                }
                extractActivities(root, activities, importedKomootActivityIds);
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

    public KomootImportExecutionResponse importFirstNewActivity(KomootImportRequest request, UUID fitPubUserId) {
        ImportCandidateContext context = buildImportCandidateContext(request, fitPubUserId);
        if (context.candidate() == null) {
            return new KomootImportExecutionResponse(
                    null,
                    null,
                    context.importedKomootActivityIds().size(),
                    context.activities().size(),
                    "No new Komoot activities found to import."
            );
        }

        KomootActivitySummaryDTO candidate = context.candidate();
        JsonNode details = fetchActivityDetails(request, candidate.id());
        byte[] gpxData = fetchActivityGpx(request, candidate.id());

        ByteArrayMultipartFile gpxFile = new ByteArrayMultipartFile(
                "file",
                "komoot-" + candidate.id() + ".gpx",
                "application/gpx+xml",
                gpxData
        );

        Activity.Visibility mappedVisibility = mapVisibility(nullableText(details, "status"));
        String mappedTitle = firstNonBlank(nullableText(details, "name"), candidate.name(), "Komoot Activity " + candidate.id());
        String mappedDescription = nullableText(details, "description");
        Activity.ActivityType mappedActivityType = mapKomootSportToActivityType(nullableText(details, "sport"));

        Activity importedActivity = activityFileService.processActivityFile(
                gpxFile,
                fitPubUserId,
                mappedTitle,
                mappedDescription,
                mappedVisibility
        );

        importedActivity.setKomootActivityId(candidate.id());
        importedActivity.setTitle(mappedTitle);
        importedActivity.setDescription(mappedDescription);
        importedActivity.setVisibility(mappedVisibility);
        importedActivity.setActivityType(mappedActivityType);

        importedActivity = activityRepository.save(importedActivity);
        activityPostProcessingService.processActivityAsync(importedActivity.getId(), fitPubUserId);

        log.info(
                "Imported Komoot activity {} into FitPub activity {} with visibility {} and type {}",
                candidate.id(),
                importedActivity.getId(),
                importedActivity.getVisibility(),
                importedActivity.getActivityType()
        );

        return new KomootImportExecutionResponse(
                importedActivity.getId(),
                candidate.id(),
                context.importedKomootActivityIds().size() + 1,
                context.activities().size(),
                "Imported Komoot activity " + candidate.id() + " into FitPub activity " + importedActivity.getId()
        );
    }

    private URI buildInitialUri(KomootImportRequest request) {
        String normalizedBaseUrl = komootBaseUrl.endsWith("/") ? komootBaseUrl.substring(0, komootBaseUrl.length() - 1) : komootBaseUrl;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(normalizedBaseUrl + "/api/v007/users/" + request.userId() + "/tours/")
                .queryParam("type", "tour_recorded")
                .queryParam("sort_field", "date")
                .queryParam("sort_direction", "desc")
                .queryParam("limit", PAGE_SIZE);

        if (request.startDate() != null && request.endDate() != null) {
            builder.queryParam("start_date", formatKomootStartDate(request.startDate()))
                    .queryParam("end_date", formatKomootEndDate(request.endDate()));
        } else {
            builder.queryParam("status", "private")
                    .queryParam("name", "")
                    .queryParam("hl", KOMOOT_LANGUAGE)
                    .queryParam("page", 0);
        }

        return builder.build().toUri();
    }

    private URI buildDetailUri(long activityId) {
        String normalizedBaseUrl = komootBaseUrl.endsWith("/") ? komootBaseUrl.substring(0, komootBaseUrl.length() - 1) : komootBaseUrl;
        return UriComponentsBuilder.fromUriString(normalizedBaseUrl + "/api/v007/tours/" + activityId)
                .queryParam("hl", KOMOOT_LANGUAGE)
                .build()
                .toUri();
    }

    private List<URI> buildGpxCandidateUris(long activityId) {
        String normalizedBaseUrl = komootBaseUrl.endsWith("/") ? komootBaseUrl.substring(0, komootBaseUrl.length() - 1) : komootBaseUrl;
        String apiBaseUrl = normalizedBaseUrl.replace("://www.komoot.com", "://api.komoot.de");

        return List.of(
                URI.create(normalizedBaseUrl + "/api/v007/tours/" + activityId + ".gpx"),
                URI.create(apiBaseUrl + "/v007/tours/" + activityId + ".gpx"),
                URI.create(normalizedBaseUrl + "/tour/" + activityId + ".gpx")
        );
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

    private HttpHeaders buildGpxHeaders(String email, String password) {
        HttpHeaders headers = buildHeaders(email, password);
        headers.setAccept(List.of(
                MediaType.parseMediaType("application/gpx+xml"),
                MediaType.APPLICATION_XML,
                MediaType.TEXT_XML
        ));
        return headers;
    }

    private String basicAuth(String email, String password) {
        String credentials = email + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private void extractActivities(JsonNode root, List<KomootActivitySummaryDTO> activities, Set<Long> importedKomootActivityIds) {
        JsonNode tours = root.path("_embedded").path("tours");
        if (!tours.isArray()) {
            return;
        }

        for (JsonNode tour : tours) {
            long activityId = tour.path("id").asLong();
            activities.add(new KomootActivitySummaryDTO(
                    activityId,
                    nullableText(tour, "name"),
                    nullableText(tour, "sport"),
                    mapKomootSportToActivityType(nullableText(tour, "sport")).name(),
                    nullableText(tour, "status"),
                    nullableText(tour, "type"),
                    parseDate(tour.path("date").asText(null)),
                    nullableDouble(tour, "distance"),
                    nullableInteger(tour, "duration"),
                    nullableInteger(tour, "time_in_motion"),
                    nullableDouble(tour, "elevation_up"),
                    importedKomootActivityIds.contains(activityId)
            ));
        }
    }

    private JsonNode fetchActivityDetails(KomootImportRequest request, long activityId) {
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    buildDetailUri(activityId),
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders(request.email(), request.password())),
                    JsonNode.class
            );

            JsonNode body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Komoot returned an empty activity detail response.");
            }
            return body;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new IllegalArgumentException("Komoot login failed while loading activity details.", e);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Komoot activity details could not be found.", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Failed to reach Komoot while loading activity details.", e);
        }
    }

    private byte[] fetchActivityGpx(KomootImportRequest request, long activityId) {
        HttpEntity<Void> httpEntity = new HttpEntity<>(buildGpxHeaders(request.email(), request.password()));
        List<URI> candidateUris = buildGpxCandidateUris(activityId);
        Exception lastException = null;

        for (URI candidateUri : candidateUris) {
            try {
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        candidateUri,
                        HttpMethod.GET,
                        httpEntity,
                        byte[].class
                );

                byte[] body = response.getBody();
                if (body == null || body.length == 0) {
                    throw new IllegalStateException("Komoot returned an empty GPX response.");
                }

                String gpxText = new String(body, StandardCharsets.UTF_8);
                if (!gpxText.contains("<gpx")) {
                    throw new IllegalStateException("Komoot response did not contain GPX XML.");
                }

                log.info("Downloaded Komoot GPX for activity {} from {}", activityId, candidateUri);
                return body;
            } catch (HttpClientErrorException.NotFound e) {
                lastException = e;
                log.debug("Komoot GPX candidate not found for activity {} at {}", activityId, candidateUri);
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
                throw new IllegalArgumentException("Komoot login failed while downloading GPX.", e);
            } catch (RestClientException | IllegalStateException e) {
                lastException = e;
                log.debug("Komoot GPX candidate failed for activity {} at {}: {}", activityId, candidateUri, e.getMessage());
            }
        }

        throw new IllegalStateException("Failed to download GPX from Komoot for activity " + activityId, lastException);
    }

    private ImportCandidateContext buildImportCandidateContext(KomootImportRequest request, UUID fitPubUserId) {
        Set<Long> importedKomootActivityIds = new HashSet<>(
                activityRepository.findImportedKomootActivityIdsByUserId(fitPubUserId));

        List<KomootActivitySummaryDTO> activities = new ArrayList<>(fetchCompletedActivities(request, fitPubUserId).activities());
        activities.sort(Comparator.comparing(
                KomootActivitySummaryDTO::date,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        KomootActivitySummaryDTO candidate = activities.stream()
                .filter(activity -> !importedKomootActivityIds.contains(activity.id()))
                .findFirst()
                .orElse(null);

        return new ImportCandidateContext(importedKomootActivityIds, activities, candidate);
    }

    private String formatKomootStartDate(LocalDate localDate) {
        return localDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .atOffset(ZoneOffset.UTC)
                .format(KOMOOT_DATE_TIME_FORMATTER);
    }

    private String formatKomootEndDate(LocalDate localDate) {
        return localDate.atTime(LocalTime.of(23, 59, 59, 999_000_000))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .atOffset(ZoneOffset.UTC)
                .format(KOMOOT_DATE_TIME_FORMATTER);
    }

    private Activity.Visibility mapVisibility(String komootStatus) {
        if (komootStatus == null) {
            return Activity.Visibility.PRIVATE;
        }

        return switch (komootStatus.toLowerCase(java.util.Locale.ROOT)) {
            case "public" -> Activity.Visibility.PUBLIC;
            case "friends", "followers", "close_friends" -> Activity.Visibility.FOLLOWERS;
            default -> Activity.Visibility.PRIVATE;
        };
    }

    private Activity.ActivityType mapKomootSportToActivityType(String komootSport) {
        if (komootSport == null || komootSport.isBlank()) {
            return Activity.ActivityType.OTHER;
        }

        return switch (komootSport.toLowerCase(java.util.Locale.ROOT)) {
            case "hike" -> Activity.ActivityType.HIKE;
            case "walk" -> Activity.ActivityType.WALK;
            case "run", "trailrunning", "jogging" -> Activity.ActivityType.RUN;
            case "touringbicycle", "road_bike", "bike", "bicycle", "gravel", "mtb", "mtb_easy", "mtb_advanced", "ebike" ->
                    Activity.ActivityType.RIDE;
            case "alpine_ski" -> Activity.ActivityType.ALPINE_SKI;
            case "backcountry_ski" -> Activity.ActivityType.BACKCOUNTRY_SKI;
            case "nordic_ski", "cross_country_ski" -> Activity.ActivityType.NORDIC_SKI;
            case "snowboard" -> Activity.ActivityType.SNOWBOARD;
            case "swim" -> Activity.ActivityType.SWIM;
            case "rowing" -> Activity.ActivityType.ROWING;
            case "kayak", "kayaking" -> Activity.ActivityType.KAYAKING;
            case "canoe", "canoeing" -> Activity.ActivityType.CANOEING;
            case "inline_skate", "inline_skating" -> Activity.ActivityType.INLINE_SKATING;
            case "rock_climbing" -> Activity.ActivityType.ROCK_CLIMBING;
            case "mountaineering" -> Activity.ActivityType.MOUNTAINEERING;
            case "yoga" -> Activity.ActivityType.YOGA;
            case "workout", "gym" -> Activity.ActivityType.WORKOUT;
            default -> Activity.ActivityType.OTHER;
        };
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private record ImportCandidateContext(
            Set<Long> importedKomootActivityIds,
            List<KomootActivitySummaryDTO> activities,
            KomootActivitySummaryDTO candidate
    ) {
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
