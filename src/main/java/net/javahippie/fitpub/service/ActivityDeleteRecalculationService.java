package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Recalculates derived analytics after an activity has been deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityDeleteRecalculationService {

    private final AchievementService achievementService;
    private final ActivitySummaryService activitySummaryService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleActivityDeleted(ActivityDeletedEvent event) {
        achievementService.rebuildAchievementsForUser(event.userId());
        activitySummaryService.updateWeeklySummary(event.userId(), event.activityDate());
        activitySummaryService.updateMonthlySummary(event.userId(), event.activityDate());
        activitySummaryService.updateYearlySummary(event.userId(), event.activityDate());
        log.info("Recalculated achievements and summaries after deleting activity for user {}", event.userId());
    }
}
