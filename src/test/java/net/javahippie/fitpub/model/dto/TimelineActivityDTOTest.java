package net.javahippie.fitpub.model.dto;

import net.javahippie.fitpub.model.entity.RemoteActivity;
import net.javahippie.fitpub.model.entity.RemoteActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TimelineActivityDTO Tests")
class TimelineActivityDTOTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    @DisplayName("Should expose remote simplified track and GPS availability without map image")
    void fromRemoteActivity_ShouldExposeSimplifiedTrack() {
        RemoteActivity remoteActivity = RemoteActivity.builder()
            .activityType("RUN")
            .title("Lunch Run")
            .activityUri("https://fitpub.example/activities/123e4567-e89b-12d3-a456-426614174000")
            .visibility(RemoteActivity.Visibility.PUBLIC)
            .simplifiedTrack(GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(8.55, 47.37),
                new Coordinate(8.56, 47.38)
            }))
            .build();

        RemoteActor actor = RemoteActor.builder()
            .username("alice")
            .build();

        TimelineActivityDTO dto = TimelineActivityDTO.fromRemoteActivity(remoteActivity, actor);

        assertThat(dto.getHasGpsTrack()).isTrue();
        assertThat(dto.getSimplifiedTrack()).isNotNull();
        assertThat(dto.getSimplifiedTrack().get("type")).isEqualTo("LineString");
    }
}
