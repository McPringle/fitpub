package net.javahippie.fitpub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import net.javahippie.fitpub.model.entity.Achievement;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivityMetrics;
import net.javahippie.fitpub.repository.AchievementRepository;
import net.javahippie.fitpub.repository.ActivityRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AchievementService.
 * Tests badge awarding logic for various achievement types.
 */
@ExtendWith(MockitoExtension.class)
class AchievementServiceTest {

    @Mock
    private AchievementRepository achievementRepository;

    @Mock
    private ActivityRepository activityRepository;

    @InjectMocks
    private AchievementService achievementService;

    private UUID userId;
    private LocalDateTime testTime;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testTime = LocalDateTime.of(2025, 12, 1, 10, 0);
    }

    @Test
    @DisplayName("Should award first activity achievement")
    void testCheckAndAwardAchievements_FirstActivity() {
        // Given
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        stubHistory(activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertFalse(achievements.isEmpty());
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.FIRST_ACTIVITY
        ));
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.FIRST_RUN
        ));
        verify(achievementRepository, atLeast(2)).save(any(Achievement.class));
    }

    @Test
    @DisplayName("Should award first run achievement")
    void testCheckAndAwardAchievements_FirstRun() {
        // Given
        Activity firstRide = createActivity(Activity.ActivityType.RIDE, 10000L, BigDecimal.ZERO);
        firstRide.setStartedAt(testTime.minusDays(1));
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);

        stubHistory(firstRide, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.FIRST_RUN
        ));
    }

    @Test
    @DisplayName("Should award distance milestone achievements")
    void testCheckAndAwardAchievements_DistanceMilestone() {
        // Given - Current activity crosses 10 km total
        Activity previous = createActivity(Activity.ActivityType.RUN, 7000L, BigDecimal.ZERO);
        previous.setStartedAt(testTime.minusDays(1));
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);

        stubHistory(previous, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.DISTANCE_10K
        ));
        assertFalse(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.DISTANCE_50K
        ));
    }

    @Test
    @DisplayName("Should award activity count milestone")
    void testCheckAndAwardAchievements_ActivityCount() {
        // Given - Current activity is the 10th activity
        List<Activity> history = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Activity previous = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
            previous.setStartedAt(testTime.minusDays(10 - i));
            history.add(previous);
        }
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        history.add(activity);

        stubHistory(history);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.ACTIVITIES_10
        ));
    }

    @Test
    @DisplayName("Should award early bird achievement")
    void testCheckAndAwardAchievements_EarlyBird() {
        // Given - Activity before 6am is the 5th early activity
        List<Activity> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Activity previous = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
            previous.setStartedAt(LocalDateTime.of(2025, 11, 20 + i, 5, 30));
            history.add(previous);
        }
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        activity.setStartedAt(LocalDateTime.of(2025, 12, 1, 5, 30)); // 5:30 AM

        history.add(activity);
        stubHistory(history);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.EARLY_BIRD
        ));
    }

    @Test
    @DisplayName("Should award night owl achievement")
    void testCheckAndAwardAchievements_NightOwl() {
        // Given - Activity after 10pm is the 5th late activity
        List<Activity> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Activity previous = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
            previous.setStartedAt(LocalDateTime.of(2025, 11, 20 + i, 23, 0));
            history.add(previous);
        }
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        activity.setStartedAt(LocalDateTime.of(2025, 12, 1, 23, 0)); // 11:00 PM

        history.add(activity);
        stubHistory(history);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.NIGHT_OWL
        ));
    }

    @Test
    @DisplayName("Should award mountaineer achievement for 1000m elevation")
    void testCheckAndAwardAchievements_Mountaineer1000m() {
        // Given - Activity with 1000m+ elevation gain
        Activity activity = createActivity(Activity.ActivityType.HIKE, 10000L, BigDecimal.valueOf(1200));

        stubHistory(activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.MOUNTAINEER_1000M
        ));
    }

    @Test
    @DisplayName("Should award total elevation milestones")
    void testCheckAndAwardAchievements_TotalElevation() {
        // Given - Current activity crosses 5000m total elevation
        Activity previous = createActivity(Activity.ActivityType.HIKE, 10000L, BigDecimal.valueOf(4500));
        previous.setStartedAt(testTime.minusDays(1));
        Activity activity = createActivity(Activity.ActivityType.HIKE, 10000L, BigDecimal.valueOf(500));

        stubHistory(previous, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.MOUNTAINEER_5000M
        ));
        assertFalse(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.MOUNTAINEER_10000M
        ));
    }

    @Test
    @DisplayName("Should award variety seeker achievement")
    void testCheckAndAwardAchievements_VarietySeeker() {
        // Given - Current activity introduces the 3rd distinct activity type
        Activity run = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        run.setStartedAt(testTime.minusDays(2));
        Activity ride = createActivity(Activity.ActivityType.RIDE, 20000L, BigDecimal.ZERO);
        ride.setStartedAt(testTime.minusDays(1));
        Activity activity = createActivity(Activity.ActivityType.SWIM, 2000L, BigDecimal.ZERO);

        stubHistory(run, ride, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.VARIETY_SEEKER
        ));
    }

    @Test
    @DisplayName("Should award speed demon achievement")
    void testCheckAndAwardAchievements_SpeedDemon() {
        // Given - Activity with 40+ km/h speed (maxSpeed is stored in km/h)
        Activity activity = createActivity(Activity.ActivityType.RIDE, 20000L, BigDecimal.ZERO);
        ActivityMetrics metrics = new ActivityMetrics();
        metrics.setMaxSpeed(BigDecimal.valueOf(45.0)); // 45 km/h (realistic for cycling)
        activity.setMetrics(metrics);

        stubHistory(activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.SPEED_DEMON
        ));
    }

    @Test
    @DisplayName("Should award 7-day streak achievement")
    void testCheckAndAwardAchievements_7DayStreak() {
        // Given - Current activity completes a 7-day streak
        List<Activity> history = new ArrayList<>();
        LocalDate anchorDate = testTime.toLocalDate();
        for (int i = 6; i >= 1; i--) {
            Activity previous = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
            previous.setStartedAt(anchorDate.minusDays(i).atTime(10, 0));
            history.add(previous);
        }
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        history.add(activity);

        stubHistory(history);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.STREAK_7_DAYS
        ));
    }

    @Test
    @DisplayName("Should NOT award achievements if already earned")
    void testCheckAndAwardAchievements_AlreadyEarned() {
        // Given - User already has every achievement
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        stubHistory(activity);
        // Simulate "user already has all achievements" by returning one of every type from the
        // preload query that checkAndAwardAchievements uses to populate the in-memory set.
        List<Achievement> allEarned = new java.util.ArrayList<>();
        for (Achievement.AchievementType type : Achievement.AchievementType.values()) {
            Achievement a = new Achievement();
            a.setUserId(userId);
            a.setAchievementType(type);
            allEarned.add(a);
        }
        when(achievementRepository.findByUserIdOrderByEarnedAtDesc(userId)).thenReturn(allEarned);

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.isEmpty());
        verify(achievementRepository, never()).save(any(Achievement.class));
    }

    @Test
    @DisplayName("Should NOT award achievements if userId is null")
    void testCheckAndAwardAchievements_NullUserId() {
        // Given
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.ZERO);
        activity.setUserId(null);

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertTrue(achievements.isEmpty());
        verify(achievementRepository, never()).save(any(Achievement.class));
    }

    @Test
    @DisplayName("Should award multiple achievements in single activity")
    void testCheckAndAwardAchievements_Multiple() {
        // Given - Activity that triggers multiple achievements
        Activity activity = createActivity(Activity.ActivityType.RUN, 5000L, BigDecimal.valueOf(1100));
        ActivityMetrics metrics = new ActivityMetrics();
        metrics.setMaxSpeed(BigDecimal.valueOf(16.0)); // 57.6 km/h (unrealistic for run, but for testing)
        activity.setMetrics(metrics);

        stubHistory(activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        // Then
        assertFalse(achievements.isEmpty());
        assertTrue(achievements.size() >= 2); // Should have multiple achievements
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.FIRST_ACTIVITY
        ));
        assertTrue(achievements.stream().anyMatch(a ->
            a.getAchievementType() == Achievement.AchievementType.MOUNTAINEER_1000M
        ));
    }

    @Test
    @DisplayName("Should use activity end time as earnedAt for historical milestone")
    void testCheckAndAwardAchievements_UsesActivityEndTimeForEarnedAt() {
        Activity previous = createActivity(Activity.ActivityType.RUN, 9000L, BigDecimal.ZERO);
        previous.setStartedAt(LocalDateTime.of(2025, 11, 30, 10, 0));
        previous.setEndedAt(LocalDateTime.of(2025, 11, 30, 11, 0));
        Activity activity = createActivity(Activity.ActivityType.RUN, 2000L, BigDecimal.ZERO);
        activity.setStartedAt(LocalDateTime.of(2025, 12, 1, 7, 15));
        activity.setEndedAt(LocalDateTime.of(2025, 12, 1, 8, 5));

        stubHistory(previous, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Achievement> achievements = achievementService.checkAndAwardAchievements(activity);

        Achievement distanceAchievement = achievements.stream()
                .filter(a -> a.getAchievementType() == Achievement.AchievementType.DISTANCE_10K)
                .findFirst()
                .orElseThrow();

        assertEquals(activity.getEndedAt(), distanceAchievement.getEarnedAt());
        assertEquals(activity.getId(), distanceAchievement.getActivityId());
    }

    @Test
    @DisplayName("Should rebuild achievements by deleting existing rows and replaying history")
    void testRebuildAchievementsForUser() {
        Activity previous = createActivity(Activity.ActivityType.RUN, 9000L, BigDecimal.ZERO);
        previous.setStartedAt(LocalDateTime.of(2025, 11, 30, 10, 0));
        previous.setEndedAt(LocalDateTime.of(2025, 11, 30, 11, 0));
        Activity activity = createActivity(Activity.ActivityType.RUN, 2000L, BigDecimal.ZERO);
        activity.setStartedAt(LocalDateTime.of(2025, 12, 1, 7, 15));
        activity.setEndedAt(LocalDateTime.of(2025, 12, 1, 8, 5));

        stubHistory(previous, activity);
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Achievement> rebuilt = achievementService.rebuildAchievementsForUser(userId);

        assertTrue(rebuilt.stream().anyMatch(a -> a.getAchievementType() == Achievement.AchievementType.DISTANCE_10K));

        InOrder inOrder = inOrder(achievementRepository);
        inOrder.verify(achievementRepository).deleteByUserId(userId);
        inOrder.verify(achievementRepository).flush();
        inOrder.verify(achievementRepository, atLeastOnce()).save(any(Achievement.class));
    }

    @Test
    @DisplayName("Should rebuild achievements from a stable history snapshot")
    void testRebuildAchievementsForUser_UsesStableHistorySnapshot() {
        Activity previous = createActivity(Activity.ActivityType.RUN, 9000L, BigDecimal.ZERO);
        previous.setStartedAt(LocalDateTime.of(2025, 11, 30, 10, 0));
        previous.setEndedAt(LocalDateTime.of(2025, 11, 30, 11, 0));
        Activity activity = createActivity(Activity.ActivityType.RUN, 2000L, BigDecimal.ZERO);
        activity.setStartedAt(LocalDateTime.of(2025, 12, 1, 7, 15));
        activity.setEndedAt(LocalDateTime.of(2025, 12, 1, 8, 5));

        when(activityRepository.findByUserIdOrderByStartedAtAsc(userId))
                .thenReturn(List.of(previous, activity))
                .thenReturn(List.of(previous));
        when(achievementRepository.save(any(Achievement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Achievement> rebuilt = achievementService.rebuildAchievementsForUser(userId);

        assertTrue(rebuilt.stream().anyMatch(a -> a.getAchievementType() == Achievement.AchievementType.DISTANCE_10K));
        verify(activityRepository, times(1)).findByUserIdOrderByStartedAtAsc(userId);
    }

    @Test
    @DisplayName("Should get user achievements")
    void testGetUserAchievements() {
        // Given
        List<Achievement> expectedAchievements = List.of(
            createAchievement(Achievement.AchievementType.FIRST_ACTIVITY),
            createAchievement(Achievement.AchievementType.FIRST_RUN)
        );

        when(achievementRepository.findByUserIdOrderByEarnedAtDesc(userId)).thenReturn(expectedAchievements);

        // When
        List<Achievement> achievements = achievementService.getUserAchievements(userId);

        // Then
        assertEquals(2, achievements.size());
        verify(achievementRepository).findByUserIdOrderByEarnedAtDesc(userId);
    }

    @Test
    @DisplayName("Should get achievement count")
    void testGetAchievementCount() {
        // Given
        when(achievementRepository.countByUserId(userId)).thenReturn(5L);

        // When
        long count = achievementService.getAchievementCount(userId);

        // Then
        assertEquals(5L, count);
        verify(achievementRepository).countByUserId(userId);
    }

    // Helper methods

    private Activity createActivity(Activity.ActivityType activityType, long distanceMeters, BigDecimal elevationGain) {
        return Activity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .activityType(activityType)
                .startedAt(testTime)
                .endedAt(testTime.plusHours(1))
                .totalDistance(BigDecimal.valueOf(distanceMeters))
                .totalDurationSeconds(3600L)
                .elevationGain(elevationGain)
                .build();
    }

    private Achievement createAchievement(Achievement.AchievementType achievementType) {
        return Achievement.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .achievementType(achievementType)
                .name("Test Achievement")
                .description("Test Description")
                .badgeIcon("🎉")
                .badgeColor("#ff00ff")
                .earnedAt(testTime)
                .build();
    }

    private void stubHistory(Activity... activities) {
        stubHistory(List.of(activities));
    }

    private void stubHistory(List<Activity> activities) {
        when(activityRepository.findByUserIdOrderByStartedAtAsc(userId)).thenReturn(activities);
    }
}
