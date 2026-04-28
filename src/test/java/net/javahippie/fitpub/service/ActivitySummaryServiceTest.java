package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivitySummary;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.ActivitySummaryRepository;
import net.javahippie.fitpub.repository.AchievementRepository;
import net.javahippie.fitpub.repository.PersonalRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivitySummaryServiceTest {

    private ActivitySummaryRepository activitySummaryRepository;
    private ActivityRepository activityRepository;
    private PersonalRecordRepository personalRecordRepository;
    private AchievementRepository achievementRepository;
    private ActivitySummaryService service;

    @BeforeEach
    void setUp() {
        activitySummaryRepository = mock(ActivitySummaryRepository.class);
        activityRepository = mock(ActivityRepository.class);
        personalRecordRepository = mock(PersonalRecordRepository.class);
        achievementRepository = mock(AchievementRepository.class);
        service = new ActivitySummaryService(
                activitySummaryRepository,
                activityRepository,
                personalRecordRepository,
                achievementRepository
        );
    }

    @Test
    @DisplayName("Should retry summary save as update when concurrent insert hits unique constraint")
    void shouldRetrySummarySaveAfterConcurrentInsert() {
        UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        LocalDate date = LocalDate.of(2025, 10, 8);
        LocalDate weekStart = LocalDate.of(2025, 10, 6);
        LocalDate weekEnd = LocalDate.of(2025, 10, 12);

        ActivitySummary existingSummary = ActivitySummary.builder()
                .id(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
                .userId(userId)
                .periodType(ActivitySummary.PeriodType.WEEK)
                .periodStart(weekStart)
                .periodEnd(weekEnd)
                .build();

        when(activitySummaryRepository.findByUserIdAndPeriodTypeAndPeriodStart(
                userId, ActivitySummary.PeriodType.WEEK, weekStart
        )).thenReturn(Optional.empty(), Optional.of(existingSummary));

        when(activityRepository.findByUserIdAndStartedAtBetweenOrderByStartedAtDesc(
                eq(userId),
                eq(weekStart.atStartOfDay()),
                eq(weekEnd.plusDays(1).atStartOfDay())
        )).thenReturn(List.of(
                Activity.builder()
                        .userId(userId)
                        .activityType(Activity.ActivityType.RIDE)
                        .startedAt(LocalDateTime.of(2025, 10, 8, 9, 0))
                        .totalDurationSeconds(3600L)
                        .build()
        ));

        when(personalRecordRepository.countByUserIdAndDateRange(any(), any(), any())).thenReturn(0L);
        when(achievementRepository.countByUserIdAndDateRange(any(), any(), any())).thenReturn(0L);

        when(activitySummaryRepository.save(any(ActivitySummary.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenReturn(existingSummary);

        service.updateWeeklySummary(userId, date);

        verify(activitySummaryRepository, times(2))
                .findByUserIdAndPeriodTypeAndPeriodStart(userId, ActivitySummary.PeriodType.WEEK, weekStart);
        verify(activitySummaryRepository, times(2)).save(any(ActivitySummary.class));
    }
}
