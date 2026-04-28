package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootImportExecutionResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.repository.ActivityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KomootImportServiceTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KomootImportService service;
    private ActivityRepository activityRepository;
    private ActivityFileService activityFileService;
    private ActivityPostProcessingService activityPostProcessingService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        activityRepository = mock(ActivityRepository.class);
        activityFileService = mock(ActivityFileService.class);
        activityPostProcessingService = mock(ActivityPostProcessingService.class);
        service = new KomootImportService(restTemplate, activityRepository, activityFileService, activityPostProcessingService);
        ReflectionTestUtils.setField(service, "komootBaseUrl", "https://www.komoot.com");
    }

    @Test
    void shouldFetchAndMergePagedCompletedActivities() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours/?type=tour_recorded&status=private&name=&hl=en&sort_field=date&sort_direction=desc&page=0&limit=100"))
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

        KomootActivitiesResponse response = service.fetchCompletedActivities(
                new KomootImportRequest("user@example.com", "secret", "123456"));

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.activities()).hasSize(2);
        assertThat(response.activities().get(0).id()).isEqualTo(1001L);
        assertThat(response.activities().get(0).timeInMotionSeconds()).isEqualTo(7800);
        assertThat(response.activities().get(1).name()).isEqualTo("Lunch Walk");

        server.verify();
    }

    @Test
    @DisplayName("Should import newest not-yet-imported Komoot activity via GPX and override metadata")
    void shouldImportNewestNotYetImportedActivity() {
        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("user@example.com:secret".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID importedActivityId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        when(activityRepository.findImportedKomootActivityIdsByUserId(userId)).thenReturn(List.of());

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours/?type=tour_recorded&status=private&name=&hl=en&sort_field=date&sort_direction=desc&page=0&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "_embedded": {
                            "tours": [
                              {
                                "id": 2880957035,
                                "name": "Latest Ride",
                                "sport": "mtb_easy",
                                "status": "public",
                                "type": "tour_recorded",
                                "date": "2026-04-27T18:15:00+02:00"
                              }
                            ]
                          },
                          "_links": {}
                        }
                        """, MediaType.APPLICATION_JSON));

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

        KomootImportExecutionResponse response = service.importFirstNewActivity(
                new KomootImportRequest("user@example.com", "secret", "123456"),
                userId
        );

        assertThat(response.importedActivityId()).isEqualTo(importedActivityId);
        assertThat(response.importedKomootActivityId()).isEqualTo(2880957035L);
        assertThat(importedActivity.getKomootActivityId()).isEqualTo(2880957035L);
        assertThat(importedActivity.getTitle()).isEqualTo("Latest Ride");
        assertThat(importedActivity.getDescription()).isEqualTo("Imported from Komoot");
        assertThat(importedActivity.getVisibility()).isEqualTo(Activity.Visibility.PUBLIC);
        assertThat(importedActivity.getActivityType()).isEqualTo(Activity.ActivityType.RIDE);

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

        when(activityRepository.findImportedKomootActivityIdsByUserId(userId)).thenReturn(List.of());

        server.expect(once(), requestTo("https://www.komoot.com/api/v007/users/123456/tours/?type=tour_recorded&status=private&name=&hl=en&sort_field=date&sort_direction=desc&page=0&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, authHeader))
                .andRespond(withSuccess("""
                        {
                          "_embedded": {
                            "tours": [
                              {
                                "id": 2880957036,
                                "name": "Unknown Sport",
                                "sport": "space_biking",
                                "status": "private",
                                "type": "tour_recorded",
                                "date": "2026-04-27T18:15:00+02:00"
                              }
                            ]
                          },
                          "_links": {}
                        }
                        """, MediaType.APPLICATION_JSON));

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

        KomootImportExecutionResponse response = service.importFirstNewActivity(
                new KomootImportRequest("user@example.com", "secret", "123456"),
                userId
        );

        assertThat(response.importedActivityId()).isEqualTo(importedActivityId);
        assertThat(importedActivity.getActivityType()).isEqualTo(Activity.ActivityType.OTHER);

        verify(activityPostProcessingService).processActivityAsync(importedActivityId, userId);
        server.verify();
    }
}
