package net.javahippie.fitpub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActivityDeleteRecalculationServiceTest {

    @Mock
    private AchievementService achievementService;

    @Mock
    private ActivitySummaryService activitySummaryService;

    @InjectMocks
    private ActivityDeleteRecalculationService activityDeleteRecalculationService;

    @Test
    @DisplayName("Should rebuild achievements and summaries after activity deletion")
    void shouldRebuildAchievementsAndSummariesAfterActivityDeletion() {
        UUID userId = UUID.randomUUID();
        LocalDate activityDate = LocalDate.of(2025, 12, 3);

        activityDeleteRecalculationService.handleActivityDeleted(new ActivityDeletedEvent(userId, activityDate));

        verify(achievementService).rebuildAchievementsForUser(userId);
        verify(activitySummaryService).updateWeeklySummary(userId, activityDate);
        verify(activitySummaryService).updateMonthlySummary(userId, activityDate);
        verify(activitySummaryService).updateYearlySummary(userId, activityDate);
    }
}
