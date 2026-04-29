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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("Should count achievements in summaries by triggering activity start date")
    void testUpdateWeeklySummary_CountsAchievementsByActivityStartDate() {
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
        when(achievementRepository.countByUserIdAndActivityStartedDateRange(userId, startDateTime, endDateTime)).thenReturn(1L);
        when(activitySummaryRepository.save(org.mockito.ArgumentMatchers.any(ActivitySummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        activitySummaryService.updateWeeklySummary(userId, weekDate);

        verify(achievementRepository).countByUserIdAndActivityStartedDateRange(userId, startDateTime, endDateTime);
        verify(activitySummaryRepository).save(org.mockito.ArgumentMatchers.argThat(summary ->
                summary.getAchievementsEarned() == 1 &&
                summary.getActivityCount() == 1
        ));
    }

    @Test
    @DisplayName("Should rebuild current month summary on demand when activities exist but summary is missing")
    void getCurrentMonthSummary_RebuildsMissingSummary() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDateTime startDateTime = monthStart.atStartOfDay();
        LocalDateTime endDateTime = monthStart.plusMonths(1).atStartOfDay();

        Activity activity = Activity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .activityType(Activity.ActivityType.RUN)
                .startedAt(startDateTime.plusDays(2).plusHours(7))
                .endedAt(startDateTime.plusDays(2).plusHours(8))
                .totalDistance(BigDecimal.valueOf(10000))
                .totalDurationSeconds(3600L)
                .elevationGain(BigDecimal.valueOf(150))
                .build();

        ActivitySummary rebuiltSummary = ActivitySummary.builder()
                .userId(userId)
                .periodType(ActivitySummary.PeriodType.MONTH)
                .periodStart(monthStart)
                .periodEnd(monthStart.plusMonths(1).minusDays(1))
                .activityCount(1)
                .build();

        when(activitySummaryRepository.findByUserIdAndPeriodTypeAndPeriodStart(
                userId,
                ActivitySummary.PeriodType.MONTH,
                monthStart
        )).thenReturn(Optional.empty(), Optional.of(rebuiltSummary));
        when(activityRepository.existsByUserIdAndStartedAtBetween(userId, startDateTime, endDateTime))
                .thenReturn(true);
        when(activityRepository.findByUserIdAndStartedAtBetweenOrderByStartedAtDesc(userId, startDateTime, endDateTime))
                .thenReturn(List.of(activity));
        when(personalRecordRepository.countByUserIdAndDateRange(userId, startDateTime, endDateTime)).thenReturn(0L);
        when(achievementRepository.countByUserIdAndActivityStartedDateRange(userId, startDateTime, endDateTime)).thenReturn(0L);
        when(activitySummaryRepository.save(org.mockito.ArgumentMatchers.any(ActivitySummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ActivitySummary result = activitySummaryService.getCurrentMonthSummary(userId);

        assertNotNull(result);
        assertEquals(1, result.getActivityCount());
        verify(activityRepository).existsByUserIdAndStartedAtBetween(userId, startDateTime, endDateTime);
    }

    @Test
    @DisplayName("Should return null for current month summary when no activities exist in the period")
    void getCurrentMonthSummary_ReturnsNullWhenNoActivitiesExist() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDateTime startDateTime = monthStart.atStartOfDay();
        LocalDateTime endDateTime = monthStart.plusMonths(1).atStartOfDay();

        when(activitySummaryRepository.findByUserIdAndPeriodTypeAndPeriodStart(
                userId,
                ActivitySummary.PeriodType.MONTH,
                monthStart
        )).thenReturn(Optional.empty());
        when(activityRepository.existsByUserIdAndStartedAtBetween(userId, startDateTime, endDateTime))
                .thenReturn(false);

        ActivitySummary result = activitySummaryService.getCurrentMonthSummary(userId);

        assertNull(result);
        verify(activityRepository).existsByUserIdAndStartedAtBetween(userId, startDateTime, endDateTime);
    }
}
