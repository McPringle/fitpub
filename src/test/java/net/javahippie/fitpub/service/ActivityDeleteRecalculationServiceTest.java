package net.javahippie.fitpub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActivityDeleteRecalculationServiceTest {

    @Mock
    private AchievementService achievementService;

    @Mock
    private ActivitySummaryService activitySummaryService;

    @Mock
    private PersonalRecordService personalRecordService;

    @InjectMocks
    private ActivityDeleteRecalculationService activityDeleteRecalculationService;

    @Test
    @DisplayName("Should rebuild achievements and summaries after activity deletion")
    void shouldRebuildAchievementsAndSummariesAfterActivityDeletion() {
        UUID userId = UUID.randomUUID();
        LocalDate activityDate = LocalDate.of(2025, 12, 3);

        activityDeleteRecalculationService.handleActivityDeleted(new ActivityDeletedEvent(userId, activityDate));

        verify(achievementService).rebuildAchievementsForUser(userId);
        verify(personalRecordService).rebuildPersonalRecordsForUser(userId);
        verify(activitySummaryService).updateWeeklySummary(userId, activityDate);
        verify(activitySummaryService).updateMonthlySummary(userId, activityDate);
        verify(activitySummaryService).updateYearlySummary(userId, activityDate);
    }

    @Test
    @DisplayName("Should serialize recalculations per user and replay queued deletions")
    void shouldSerializeRecalculationsPerUserAndReplayQueuedDeletions() {
        UUID userId = UUID.randomUUID();
        LocalDate firstDate = LocalDate.of(2025, 12, 3);
        LocalDate secondDate = LocalDate.of(2025, 12, 4);
        AtomicBoolean queuedSecondDelete = new AtomicBoolean(false);

        doAnswer(invocation -> {
            if (queuedSecondDelete.compareAndSet(false, true)) {
                activityDeleteRecalculationService.handleActivityDeleted(new ActivityDeletedEvent(userId, secondDate));
            }
            return null;
        }).when(achievementService).rebuildAchievementsForUser(userId);

        activityDeleteRecalculationService.handleActivityDeleted(new ActivityDeletedEvent(userId, firstDate));

        verify(achievementService, times(2)).rebuildAchievementsForUser(userId);
        verify(personalRecordService, times(2)).rebuildPersonalRecordsForUser(userId);
        verify(activitySummaryService).updateWeeklySummary(userId, firstDate);
        verify(activitySummaryService).updateMonthlySummary(userId, firstDate);
        verify(activitySummaryService).updateYearlySummary(userId, firstDate);
        verify(activitySummaryService).updateWeeklySummary(userId, secondDate);
        verify(activitySummaryService).updateMonthlySummary(userId, secondDate);
        verify(activitySummaryService).updateYearlySummary(userId, secondDate);
    }
}
