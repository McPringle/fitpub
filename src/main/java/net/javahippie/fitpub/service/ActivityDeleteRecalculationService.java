package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Recalculates derived analytics after an activity has been deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityDeleteRecalculationService {

    private final AchievementService achievementService;
    private final ActivitySummaryService activitySummaryService;
    private final ConcurrentMap<UUID, PendingUserRecalculation> pendingRecalculations = new ConcurrentHashMap<>();

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleActivityDeleted(ActivityDeletedEvent event) {
        PendingUserRecalculation pending = pendingRecalculations.computeIfAbsent(
                event.userId(),
                ignored -> new PendingUserRecalculation()
        );

        if (!pending.enqueueAndShouldStart(event.activityDate())) {
            log.debug("Queued additional activity delete recalculation for user {}", event.userId());
            return;
        }

        do {
            Set<LocalDate> datesToRecalculate = pending.drainDates();
            achievementService.rebuildAchievementsForUser(event.userId());
            for (LocalDate date : datesToRecalculate) {
                activitySummaryService.updateWeeklySummary(event.userId(), date);
                activitySummaryService.updateMonthlySummary(event.userId(), date);
                activitySummaryService.updateYearlySummary(event.userId(), date);
            }
        } while (pending.keepProcessing());

        log.info("Recalculated achievements and summaries after deleting activities for user {}", event.userId());
    }

    private static final class PendingUserRecalculation {
        private final Set<LocalDate> pendingDates = new HashSet<>();
        private boolean processing;

        synchronized boolean enqueueAndShouldStart(LocalDate activityDate) {
            pendingDates.add(activityDate);
            if (processing) {
                return false;
            }
            processing = true;
            return true;
        }

        synchronized Set<LocalDate> drainDates() {
            Set<LocalDate> dates = new HashSet<>(pendingDates);
            pendingDates.clear();
            return dates;
        }

        synchronized boolean keepProcessing() {
            if (pendingDates.isEmpty()) {
                processing = false;
                return false;
            }
            return true;
        }
    }
}
