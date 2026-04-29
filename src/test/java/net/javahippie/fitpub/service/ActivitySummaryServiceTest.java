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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivitySummaryServiceTest {

    @Mock
    private ActivitySummaryRepository activitySummaryRepository;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private PersonalRecordRepository personalRecordRepository;

    @Mock
    private AchievementRepository achievementRepository;

    @InjectMocks
    private ActivitySummaryService activitySummaryService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should retry summary save as update when concurrent insert hits unique constraint")
    void shouldRetrySummarySaveAfterConcurrentInsert() {
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
                userId,
                ActivitySummary.PeriodType.WEEK,
                weekStart
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
        when(achievementRepository.countByUserIdAndActivityStartedDateRange(any(), any(), any())).thenReturn(0L);
        when(activitySummaryRepository.save(any(ActivitySummary.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenReturn(existingSummary);

        activitySummaryService.updateWeeklySummary(userId, date);

        verify(activitySummaryRepository, times(2))
                .findByUserIdAndPeriodTypeAndPeriodStart(userId, ActivitySummary.PeriodType.WEEK, weekStart);
        verify(activitySummaryRepository, times(2)).save(any(ActivitySummary.class));
    }

    @Test
    @DisplayName("Should count achievements in summaries by triggering activity start date")
    void shouldCountAchievementsByActivityStartDate() {
        LocalDate weekDate = LocalDate.of(2025, 12, 3);
        LocalDate weekStart = LocalDate.of(2025, 12, 1);
        LocalDateTime startDateTime = weekStart.atStartOfDay();
        LocalDateTime endDateTime = weekStart.plusDays(7).atStartOfDay();

        Activity activity = Activity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .activityType(Activity.ActivityType.RUN)
                .startedAt(LocalDateTime.of(2025, 12, 3, 23, 30))
                .endedAt(LocalDateTime.of(2025, 12, 4, 0, 15))
                .totalDistance(BigDecimal.valueOf(5000))
                .totalDurationSeconds(2700L)
                .elevationGain(BigDecimal.valueOf(120))
                .build();

        when(activitySummaryRepository.findByUserIdAndPeriodTypeAndPeriodStart(
                userId,
                ActivitySummary.PeriodType.WEEK,
                weekStart
        )).thenReturn(Optional.empty());
        when(activityRepository.findByUserIdAndStartedAtBetweenOrderByStartedAtDesc(
                userId,
                startDateTime,
                endDateTime
        )).thenReturn(List.of(activity));
        when(personalRecordRepository.countByUserIdAndDateRange(userId, startDateTime, endDateTime)).thenReturn(0L);
        when(achievementRepository.countByUserIdAndActivityStartedDateRange(userId, startDateTime, endDateTime))
                .thenReturn(1L);
        when(activitySummaryRepository.save(any(ActivitySummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        activitySummaryService.updateWeeklySummary(userId, weekDate);

        verify(achievementRepository).countByUserIdAndActivityStartedDateRange(userId, startDateTime, endDateTime);
        verify(activitySummaryRepository).save(argThat(summary ->
                summary.getAchievementsEarned() == 1 &&
                summary.getActivityCount() == 1
        ));
    }
}
