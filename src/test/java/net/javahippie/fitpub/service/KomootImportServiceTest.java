package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.dto.KomootActivityImportRequest;
import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootImportExecutionResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.KomootImport;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.KomootImportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KomootImportServiceTest {

    private static KomootImportRepository.KomootImportLinkProjection importLink(UUID activityId, Long komootActivityId) {
        return new KomootImportRepository.KomootImportLinkProjection() {
            @Override
            public UUID getActivityId() {
                return activityId;
            }

            @Override
            public Long getKomootActivityId() {
                return komootActivityId;
            }
        };
    }

    private MockRestServiceServer server;
    private KomootImportService service;
    private ActivityRepository activityRepository;
    private KomootImportRepository komootImportRepository;
    private ActivityFileService activityFileService;
    private ActivityPostProcessingService activityPostProcessingService;
    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"));
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        activityRepository = mock(ActivityRepository.class);
        komootImportRepository = mock(KomootImportRepository.class);
        activityFileService = mock(ActivityFileService.class);
        activityPostProcessingService = mock(ActivityPostProcessingService.class);
        service = new KomootImportService(restTemplate, activityRepository, komootImportRepository, activityFileService, activityPostProcessingService);
        ReflectionTestUtils.setField(service, "komootBaseUrl", "https://www.komoot.com");
        ReflectionTestUtils.setField(service, "paginatedRequestDelayMillis", 0L);
        ReflectionTestUtils.setField(service, "detailToGpxDelayMillis", 0L);
        ReflectionTestUtils.setField(service, "activityImportDelayMillis", 0L);
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void shouldFetchAndMergePagedCompletedActivities() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        KomootImportService throttledService = spy(service);
        doNothing().when(throttledService).pauseBeforeNextPageRequest();
        UUID existingActivityId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

        when(komootImportRepository.findImportedKomootActivityIdsByUserId(userId)).thenReturn(List.of(1002L));
        when(komootImportRepository.findKomootImportLinksByUserIdAndKomootActivityIdIn(userId, List.of(1002L)))
                .thenReturn(List.of(importLink(existingActivityId, 1002L)));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours/?type=tour_recorded&sort_field=date&sort_direction=desc&limit=100&status=private&name=&hl=en&page=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "_embedded": {
                            "tours": [
                              {
                                "id": 1001,
                                "name": "Evening Ride",
                                "sport": "touringbicycle",
                                "status": "private",
                                "type": "tour_recorded",
                                "date": "2026-04-27T18:15:00+02:00",
                                "distance": 42350.4,
                                "duration": 8120,
                                "time_in_motion": 7800,
                                "elevation_up": 520.2
                              }
                            ]
                          },
                          "_links": {
                            "next": {
                              "href": "/api/v007/users/123456/tours?type=tour_recorded&page=1&limit=100"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours?type=tour_recorded&page=1&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "_embedded": {
                            "tours": [
                              {
                                "id": 1002,
                                "name": "Lunch Walk",
                                "sport": "hike",
                                "status": "private",
                                "type": "tour_recorded",
                                "date": "2026-04-26T12:30:00+02:00",
                                "distance": 5120.0,
                                "duration": 3600,
                                "time_in_motion": 3400,
                                "elevation_up": 75.0
                              }
                            ]
                          },
                          "_links": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        KomootActivitiesResponse response = throttledService.fetchCompletedActivities(
                new KomootImportRequest("user@example.com", "secret", "123456", null, null),
                userId);

        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getActivities()).hasSize(2);
        assertThat(response.getActivities().get(0).getId()).isEqualTo(1001L);
        assertThat(response.getActivities().get(0).isImported()).isFalse();
        assertThat(response.getActivities().get(0).getFitPubActivityId()).isNull();
        assertThat(response.getActivities().get(0).getTimeInMotionSeconds()).isEqualTo(7800);
        assertThat(response.getActivities().get(1).getName()).isEqualTo("Lunch Walk");
        assertThat(response.getActivities().get(1).isImported()).isTrue();
        assertThat(response.getActivities().get(1).getFitPubActivityId()).isEqualTo(existingActivityId);

        verify(throttledService).pauseBeforeNextPageRequest();
        server.verify();
    }

    @Test
    @DisplayName("Should filter loaded Komoot activities by inclusive date range")
    void shouldFilterCompletedActivitiesByInclusiveDateRange() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID existingActivityId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        when(komootImportRepository.findImportedKomootActivityIdsByUserId(userId)).thenReturn(List.of(1003L));
        when(komootImportRepository.findKomootImportLinksByUserIdAndKomootActivityIdIn(userId, List.of(1003L)))
                .thenReturn(List.of(importLink(existingActivityId, 1003L)));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours/?type=tour_recorded&sort_field=date&sort_direction=desc&limit=100&start_date=2026-04-25T22:00:00.000Z&end_date=2026-04-27T21:59:59.999Z"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "_embedded": {
                            "tours": [
                              {
                                "id": 1002,
                                "name": "Included Start",
                                "sport": "hike",
                                "status": "private",
                                "type": "tour_recorded",
                                "date": "2026-04-26T00:00:00+02:00"
                              },
                              {
                                "id": 1003,
                                "name": "Included End",
                                "sport": "run",
                                "status": "private",
                                "type": "tour_recorded",
                                "date": "2026-04-27T23:59:59+02:00"
                              }
                            ]
                          },
                          "_links": {}
                        }
                        """, MediaType.APPLICATION_JSON));

        KomootActivitiesResponse response = service.fetchCompletedActivities(
                new KomootImportRequest(
                        "user@example.com",
                        "secret",
                        "123456",
                        LocalDate.of(2026, 4, 26),
                        LocalDate.of(2026, 4, 27)
                ),
                userId);

        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getActivities()).extracting("id").containsExactly(1002L, 1003L);
        assertThat(response.getActivities().get(0).isImported()).isFalse();
        assertThat(response.getActivities().get(1).isImported()).isTrue();
        assertThat(response.getActivities().get(1).getFitPubActivityId()).isEqualTo(existingActivityId);

        server.verify();
    }

    @Test
    @DisplayName("Should reject incomplete Komoot date range")
    void shouldRejectIncompleteDateRange() {
        assertThatThrownBy(() -> new KomootImportRequest(
                "user@example.com",
                "secret",
                "123456",
                LocalDate.of(2026, 4, 27),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Start date and end date must either both be set or both be empty.");
    }

    @Test
    @DisplayName("Should import a specific Komoot activity via GPX and override metadata")
    void shouldImportSpecificKomootActivity() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID importedActivityId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        KomootImportService throttledService = spy(service);
        doNothing().when(throttledService).pauseBetweenDetailAndGpxRequest();
        doNothing().when(throttledService).pauseAfterActivityImport();

        when(komootImportRepository.findByUserIdAndKomootActivityId(userId, 2880957035L)).thenReturn(Optional.empty());

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957035?hl=en"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "id": "2880957035",
                          "name": "Latest Ride",
                          "description": "Imported from Komoot",
                          "status": "public",
                          "sport": "mtb_easy"
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957035.gpx"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(header(HttpHeaders.ACCEPT, "application/gpx+xml, application/xml, text/xml"))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <gpx version="1.1" creator="komoot">
                          <trk><name>Latest Ride</name></trk>
                        </gpx>
                        """, MediaType.APPLICATION_XML));

        Activity importedActivity = Activity.builder()
                .id(importedActivityId)
                .userId(userId)
                .activityType(Activity.ActivityType.OTHER)
                .title("GPX Title")
                .description(null)
                .visibility(Activity.Visibility.PRIVATE)
                .sourceFileFormat("GPX")
                .build();

        when(activityFileService.processActivityFile(any(), any(), any(), any(), any())).thenReturn(importedActivity);
        when(activityRepository.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(komootImportRepository.save(any(KomootImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KomootImportExecutionResponse response = throttledService.importActivity(
                new KomootActivityImportRequest("user@example.com", "secret", "123456", 2880957035L),
                userId
        );

        assertThat(response.getImportedActivityId()).isEqualTo(importedActivityId);
        assertThat(response.getImportedKomootActivityId()).isEqualTo(2880957035L);
        assertThat(response.getStatus()).isEqualTo("IMPORTED");
        assertThat(importedActivity.getTitle()).isEqualTo("Latest Ride");
        assertThat(importedActivity.getDescription()).isEqualTo("Imported from Komoot");
        assertThat(importedActivity.getVisibility()).isEqualTo(Activity.Visibility.PUBLIC);
        assertThat(importedActivity.getActivityType()).isEqualTo(Activity.ActivityType.RIDE);
        verify(komootImportRepository).save(any(KomootImport.class));

        verify(throttledService).pauseBetweenDetailAndGpxRequest();
        verify(throttledService).pauseAfterActivityImport();
        verify(activityPostProcessingService).processActivityAsync(importedActivityId, userId);
        server.verify();
    }

    @Test
    @DisplayName("Should skip already imported Komoot activity")
    void shouldSkipAlreadyImportedKomootActivity() {
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID existingActivityId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        when(komootImportRepository.findByUserIdAndKomootActivityId(userId, 3002L)).thenReturn(
                Optional.of(KomootImport.builder().activityId(existingActivityId).userId(userId).komootActivityId(3002L).build())
        );

        KomootImportExecutionResponse response = service.importActivity(
                new KomootActivityImportRequest("user@example.com", "secret", "123456", 3002L),
                userId
        );

        assertThat(response.getImportedActivityId()).isEqualTo(existingActivityId);
        assertThat(response.getImportedKomootActivityId()).isEqualTo(3002L);
        assertThat(response.getStatus()).isEqualTo("SKIPPED_ALREADY_IMPORTED");
    }

    @Test
    @DisplayName("Should map Komoot cycling sport racebike to ride")
    void shouldMapKomootRacebikeToRide() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID importedActivityId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        KomootImportService throttledService = spy(service);
        doNothing().when(throttledService).pauseBetweenDetailAndGpxRequest();
        doNothing().when(throttledService).pauseAfterActivityImport();

        when(komootImportRepository.findByUserIdAndKomootActivityId(userId, 2880957037L)).thenReturn(Optional.empty());

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957037?hl=en"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "id": "2880957037",
                          "name": "Road Ride",
                          "description": "Komoot road cycling type",
                          "status": "private",
                          "sport": "racebike"
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957037.gpx"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(header(HttpHeaders.ACCEPT, "application/gpx+xml, application/xml, text/xml"))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <gpx version="1.1" creator="komoot">
                          <trk><name>Road Ride</name></trk>
                        </gpx>
                        """, MediaType.APPLICATION_XML));

        Activity importedActivity = Activity.builder()
                .id(importedActivityId)
                .userId(userId)
                .activityType(Activity.ActivityType.OTHER)
                .title("GPX Title")
                .description(null)
                .visibility(Activity.Visibility.PRIVATE)
                .sourceFileFormat("GPX")
                .build();

        when(activityFileService.processActivityFile(any(), any(), any(), any(), any())).thenReturn(importedActivity);
        when(activityRepository.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(komootImportRepository.save(any(KomootImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KomootImportExecutionResponse response = throttledService.importActivity(
                new KomootActivityImportRequest("user@example.com", "secret", "123456", 2880957037L),
                userId
        );

        assertThat(response.getImportedActivityId()).isEqualTo(importedActivityId);
        assertThat(response.getStatus()).isEqualTo("IMPORTED");
        assertThat(importedActivity.getActivityType()).isEqualTo(Activity.ActivityType.RIDE);
        verify(komootImportRepository).save(any(KomootImport.class));

        verify(throttledService).pauseBetweenDetailAndGpxRequest();
        verify(throttledService).pauseAfterActivityImport();
        verify(activityPostProcessingService).processActivityAsync(importedActivityId, userId);
        server.verify();
    }

    @Test
    @DisplayName("Should fall back to OTHER when Komoot sport cannot be mapped")
    void shouldFallbackToOtherForUnknownKomootSport() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID importedActivityId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        KomootImportService throttledService = spy(service);
        doNothing().when(throttledService).pauseBetweenDetailAndGpxRequest();
        doNothing().when(throttledService).pauseAfterActivityImport();

        when(komootImportRepository.findByUserIdAndKomootActivityId(userId, 2880957036L)).thenReturn(Optional.empty());

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957036?hl=en"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "id": "2880957036",
                          "name": "Unknown Sport",
                          "description": "No mapping available",
                          "status": "private",
                          "sport": "space_biking"
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/tours/2880957036.gpx"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(header(HttpHeaders.ACCEPT, "application/gpx+xml, application/xml, text/xml"))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <gpx version="1.1" creator="komoot">
                          <trk><name>Unknown Sport</name></trk>
                        </gpx>
                        """, MediaType.APPLICATION_XML));

        Activity importedActivity = Activity.builder()
                .id(importedActivityId)
                .userId(userId)
                .activityType(Activity.ActivityType.RIDE)
                .title("GPX Title")
                .description(null)
                .visibility(Activity.Visibility.PUBLIC)
                .sourceFileFormat("GPX")
                .build();

        when(activityFileService.processActivityFile(any(), any(), any(), any(), any())).thenReturn(importedActivity);
        when(activityRepository.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(komootImportRepository.save(any(KomootImport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KomootImportExecutionResponse response = throttledService.importActivity(
                new KomootActivityImportRequest("user@example.com", "secret", "123456", 2880957036L),
                userId
        );

        assertThat(response.getImportedActivityId()).isEqualTo(importedActivityId);
        assertThat(response.getStatus()).isEqualTo("IMPORTED");
        assertThat(importedActivity.getActivityType()).isEqualTo(Activity.ActivityType.OTHER);
        verify(komootImportRepository).save(any(KomootImport.class));

        verify(throttledService).pauseBetweenDetailAndGpxRequest();
        verify(throttledService).pauseAfterActivityImport();
        verify(activityPostProcessingService).processActivityAsync(importedActivityId, userId);
        server.verify();
    }
}
