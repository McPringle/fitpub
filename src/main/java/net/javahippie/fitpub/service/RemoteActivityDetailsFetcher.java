package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.dto.ActivityDTO;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fetches FitPub-specific remote activity details from the sender's public API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RemoteActivityDetailsFetcher {

    private static final GeometryFactory GEOMETRY_FACTORY =
        new GeometryFactory(new PrecisionModel(), 4326);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Optional<RemoteActivityEnrichment> fetch(String activityUri) {
        Optional<String> endpoint = deriveApiEndpoint(activityUri);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");

            ResponseEntity<String> response = restTemplate.exchange(
                endpoint.get(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().isBlank()) {
                throw new IllegalStateException("Empty remote activity details response from " + endpoint.get());
            }

            ActivityDTO dto = objectMapper.readValue(response.getBody(), ActivityDTO.class);
            return Optional.of(map(dto));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.debug("Remote activity details unavailable via {}: {}", endpoint.get(), e.getStatusCode());
                return Optional.empty();
            }
            throw e;
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw new RuntimeException("Failed to fetch remote activity details from " + endpoint.get(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse remote activity details from " + endpoint.get(), e);
        }
    }

    private Optional<String> deriveApiEndpoint(String activityUri) {
        try {
            URI uri = URI.create(activityUri);
            String path = uri.getPath();
            if (path == null || !path.startsWith("/activities/")) {
                return Optional.empty();
            }

            String idPart = path.substring("/activities/".length());
            UUID.fromString(idPart);
            return Optional.of(uri.getScheme() + "://" + uri.getAuthority() + "/api/activities/" + idPart);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private RemoteActivityEnrichment map(ActivityDTO dto) {
        return RemoteActivityEnrichment.builder()
            .activityType(normalizeActivityType(dto.getActivityType()))
            .title(dto.getTitle())
            .description(dto.getDescription())
            .totalDistance(toLong(dto.getTotalDistance()))
            .totalDurationSeconds(dto.getTotalDurationSeconds())
            .elevationGain(toInteger(dto.getElevationGain()))
            .averagePaceSeconds(dto.getMetrics() != null ? dto.getMetrics().getAveragePaceSeconds() : null)
            .averageHeartRate(dto.getAverageHeartRate())
            .maxSpeed(toDouble(dto.getMaxSpeed()))
            .averageSpeed(toDouble(dto.getAverageSpeed()))
            .calories(dto.getCalories())
            .simplifiedTrack(toLineString(dto.getSimplifiedTrack()))
            .build();
    }

    private String normalizeActivityType(String activityType) {
        if (activityType == null || activityType.isBlank()) {
            return null;
        }
        return activityType.trim().toUpperCase().replace(' ', '_');
    }

    private Long toLong(BigDecimal value) {
        return value != null ? value.longValue() : null;
    }

    private Integer toInteger(BigDecimal value) {
        return value != null ? value.intValue() : null;
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    private LineString toLineString(Map<String, Object> simplifiedTrack) {
        if (simplifiedTrack == null || !"LineString".equals(simplifiedTrack.get("type"))) {
            return null;
        }

        Object coordinatesObject = simplifiedTrack.get("coordinates");
        if (!(coordinatesObject instanceof List<?> coordinateList) || coordinateList.size() < 2) {
            return null;
        }

        Coordinate[] coordinates = coordinateList.stream()
            .map(this::toCoordinate)
            .filter(java.util.Objects::nonNull)
            .toArray(Coordinate[]::new);

        if (coordinates.length < 2) {
            return null;
        }

        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    private Coordinate toCoordinate(Object coordinateObject) {
        if (!(coordinateObject instanceof List<?> values) || values.size() < 2) {
            return null;
        }
        if (!(values.get(0) instanceof Number longitude) || !(values.get(1) instanceof Number latitude)) {
            return null;
        }
        return new Coordinate(longitude.doubleValue(), latitude.doubleValue());
    }
}
