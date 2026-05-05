package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteActivityDetailsFetcher Tests")
class RemoteActivityDetailsFetcherTest {

    @Mock
    private RestTemplate restTemplate;

    private RemoteActivityDetailsFetcher remoteActivityDetailsFetcher;

    @BeforeEach
    void setUp() {
        remoteActivityDetailsFetcher = new RemoteActivityDetailsFetcher(restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("Should fetch and map FitPub activity details")
    void fetch_ShouldMapActivityDetails() {
        String endpoint = "https://fitpub.example/api/activities/123e4567-e89b-12d3-a456-426614174000";
        String json = """
            {
              "id": "123e4567-e89b-12d3-a456-426614174000",
              "activityType": "RUN",
              "title": "Lunch Run",
              "description": "Sunny run",
              "totalDistance": 5000,
              "totalDurationSeconds": 1800,
              "elevationGain": 100,
              "metrics": {
                "averagePaceSeconds": 321,
                "averageHeartRate": 150,
                "averageSpeed": 10.4,
                "maxSpeed": 14.2,
                "calories": 420
              },
              "simplifiedTrack": {
                "type": "LineString",
                "coordinates": [[8.55, 47.37], [8.56, 47.38]]
              }
            }
            """;

        when(restTemplate.exchange(eq(endpoint), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));

        Optional<RemoteActivityEnrichment> enrichment = remoteActivityDetailsFetcher.fetch(endpoint);

        assertThat(enrichment).isPresent();
        assertThat(enrichment.get().activityType()).isEqualTo("RUN");
        assertThat(enrichment.get().title()).isEqualTo("Lunch Run");
        assertThat(enrichment.get().description()).isEqualTo("Sunny run");
        assertThat(enrichment.get().totalDistance()).isEqualTo(5000L);
        assertThat(enrichment.get().totalDurationSeconds()).isEqualTo(1800L);
        assertThat(enrichment.get().elevationGain()).isEqualTo(100);
        assertThat(enrichment.get().averagePaceSeconds()).isEqualTo(321L);
        assertThat(enrichment.get().averageHeartRate()).isEqualTo(150);
        assertThat(enrichment.get().averageSpeed()).isEqualTo(10.4);
        assertThat(enrichment.get().maxSpeed()).isEqualTo(14.2);
        assertThat(enrichment.get().calories()).isEqualTo(420);
        assertThat(enrichment.get().simplifiedTrack()).isNotNull();
        assertThat(enrichment.get().simplifiedTrack().getNumPoints()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should skip enrichment when detail URI is missing")
    void fetch_ShouldSkipMissingDetailUri() {
        assertThat(remoteActivityDetailsFetcher.fetch(null)).isEmpty();
        assertThat(remoteActivityDetailsFetcher.fetch(" ")).isEmpty();
    }
}
