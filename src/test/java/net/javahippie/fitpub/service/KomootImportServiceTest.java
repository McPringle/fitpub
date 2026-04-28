package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KomootImportServiceTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KomootImportService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new KomootImportService(restTemplate);
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
}
