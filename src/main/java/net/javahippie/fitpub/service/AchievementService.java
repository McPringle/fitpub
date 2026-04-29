package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Achievement;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.repository.AchievementRepository;
import net.javahippie.fitpub.repository.ActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Service for managing achievements and badges.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AchievementService {

    private final AchievementRepository achievementRepository;
    private final ActivityRepository activityRepository;

    /**
     * Check and award achievements for an activity.
     * Called after an activity is saved.
     *
     * <p>The user's existing achievement set is loaded once at the start of this
     * method and threaded through every sub-check. Previously each {@code hasAchievement}
     * call hit the DB individually, which meant 16+ {@code SELECT EXISTS} queries per
     * activity upload (5 distance milestones × 5 count milestones × 4 streak milestones
     * × 1 variety × 1 speed × up to 2 time-based + 3 elevation). Now: 1 query.
     *
     * @param activity the activity to check for achievements
     * @return list of newly earned achievements
     */
    @Transactional
    public List<Achievement> checkAndAwardAchievements(Activity activity) {
        List<Achievement> newAchievements = new ArrayList<>();

        if (activity.getUserId() == null || activity.getStartedAt() == null || activity.getEndedAt() == null) {
            return newAchievements;
        }

        UUID userId = activity.getUserId();
        List<Activity> activityHistory = activityRepository.findByUserIdOrderByStartedAtAsc(userId);
        ActivityProgress progress = ActivityProgress.fromHistory(activityHistory, activity);

        // Load all of the user's existing achievement types in a single query so the
        // sub-checks below can do an in-memory `contains()` instead of an EXISTS query
        // per milestone.
        Set<Achievement.AchievementType> existing = EnumSet.noneOf(Achievement.AchievementType.class);
        for (Achievement a : achievementRepository.findByUserIdOrderByEarnedAtDesc(userId)) {
            existing.add(a.getAchievementType());
        }

        // Check first activity achievements
        newAchievements.addAll(checkFirstActivityAchievements(userId, activity, progress, existing));

        // Check distance milestones
        newAchievements.addAll(checkDistanceMilestones(userId, activity, progress, existing));

        // Check activity count milestones
        newAchievements.addAll(checkActivityCountMilestones(userId, activity, progress, existing));

        // Check streak achievements
        newAchievements.addAll(checkStreakAchievements(userId, activity, progress, existing));

        // Check time-based achievements
        newAchievements.addAll(checkTimeBasedAchievements(userId, activity, progress, existing));

        // Check elevation achievements
        newAchievements.addAll(checkElevationAchievements(userId, activity, progress, existing));

        // Check variety achievements
        newAchievements.addAll(checkVarietyAchievements(userId, activity, progress, existing));

        // Check speed achievements
        newAchievements.addAll(checkSpeedAchievements(userId, activity, progress, existing));

        return newAchievements;
    }

    /**
     * Rebuild all achievements for a user from chronological activity history.
     */
    @Transactional
    public List<Achievement> rebuildAchievementsForUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }

        List<Activity> activityHistory = activityRepository.findByUserIdOrderByStartedAtAsc(userId);
        if (activityHistory.isEmpty()) {
            achievementRepository.deleteByUserId(userId);
            return List.of();
        }

        achievementRepository.deleteByUserId(userId);

        List<Achievement> rebuiltAchievements = new ArrayList<>();
        for (Activity activity : activityHistory) {
            rebuiltAchievements.addAll(checkAndAwardAchievements(activity));
        }

        return rebuiltAchievements;
    }

    /**
     * Check first activity achievements.
     */
    private List<Achievement> checkFirstActivityAchievements(UUID userId, Activity activity,
                                                             ActivityProgress progress,
                                                             Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        // First activity overall
        if (progress.previousActivityCount() == 0 &&
            progress.currentActivityCount() == 1 &&
            !existing.contains(Achievement.AchievementType.FIRST_ACTIVITY)) {
            achievements.add(awardAchievement(
                    userId,
                    Achievement.AchievementType.FIRST_ACTIVITY,
                    "First Steps",
                    "Completed your first activity!",
                    "🎉",
                    "#ff00ff",
                    activity.getId(),
                    activity.getEndedAt(),
                    null
            ));
        }

        // First activity by type
        String activityType = activity.getActivityType().name();
        long previousTypeCount = progress.previousActivityTypeCount(activity.getActivityType());
        long currentTypeCount = progress.currentActivityTypeCount(activity.getActivityType());

        if (previousTypeCount == 0 && currentTypeCount == 1) {
            Achievement.AchievementType achievementType = switch (activityType) {
                case "RUN" -> Achievement.AchievementType.FIRST_RUN;
                case "RIDE" -> Achievement.AchievementType.FIRST_RIDE;
                case "HIKE" -> Achievement.AchievementType.FIRST_HIKE;
                default -> null;
            };

            if (achievementType != null && !existing.contains(achievementType)) {
                achievements.add(awardAchievement(
                        userId,
                        achievementType,
                        "First " + activityType.toLowerCase(),
                        "Completed your first " + activityType.toLowerCase() + "!",
                        getActivityEmoji(activityType),
                        "#00ffff",
                        activity.getId(),
                        activity.getEndedAt(),
                        null
                ));
            }
        }

        return achievements;
    }

    /**
     * Check distance milestone achievements.
     */
    private List<Achievement> checkDistanceMilestones(UUID userId, Activity activity,
                                                      ActivityProgress progress,
                                                      Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        double previousKm = progress.previousDistanceMeters().doubleValue() / 1000.0;
        double currentKm = progress.currentDistanceMeters().doubleValue() / 1000.0;

        // Check milestones
        Map<Double, Achievement.AchievementType> milestones = Map.of(
                10.0, Achievement.AchievementType.DISTANCE_10K,
                50.0, Achievement.AchievementType.DISTANCE_50K,
                100.0, Achievement.AchievementType.DISTANCE_100K,
                500.0, Achievement.AchievementType.DISTANCE_500K,
                1000.0, Achievement.AchievementType.DISTANCE_1000K
        );

        for (Map.Entry<Double, Achievement.AchievementType> entry : milestones.entrySet()) {
            if (previousKm < entry.getKey() &&
                currentKm >= entry.getKey() &&
                !existing.contains(entry.getValue())) {
                achievements.add(awardAchievement(
                        userId,
                        entry.getValue(),
                        String.format("%.0f km Total", entry.getKey()),
                        String.format("Reached %.0f kilometers total distance!", entry.getKey()),
                        "🏃",
                        "#ffff00",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("distance_km", entry.getKey())
                ));
            }
        }

        return achievements;
    }

    /**
     * Check activity count milestone achievements.
     */
    private List<Achievement> checkActivityCountMilestones(UUID userId, Activity activity,
                                                           ActivityProgress progress,
                                                           Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        Map<Long, Achievement.AchievementType> milestones = Map.of(
                10L, Achievement.AchievementType.ACTIVITIES_10,
                50L, Achievement.AchievementType.ACTIVITIES_50,
                100L, Achievement.AchievementType.ACTIVITIES_100,
                500L, Achievement.AchievementType.ACTIVITIES_500,
                1000L, Achievement.AchievementType.ACTIVITIES_1000
        );

        for (Map.Entry<Long, Achievement.AchievementType> entry : milestones.entrySet()) {
            if (progress.previousActivityCount() < entry.getKey() &&
                progress.currentActivityCount() >= entry.getKey() &&
                !existing.contains(entry.getValue())) {
                achievements.add(awardAchievement(
                        userId,
                        entry.getValue(),
                        String.format("%d Activities", entry.getKey()),
                        String.format("Completed %d activities!", entry.getKey()),
                        "💪",
                        "#ff6600",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("activity_count", entry.getKey())
                ));
            }
        }

        return achievements;
    }

    /**
     * Check streak achievements (consecutive days).
     */
    private List<Achievement> checkStreakAchievements(UUID userId, Activity activity,
                                                      ActivityProgress progress,
                                                      Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        int previousStreak = calculateStreak(progress.previousActivityDates(), activity.getEndedAt().toLocalDate());
        int currentStreak = calculateStreak(progress.currentActivityDates(), activity.getEndedAt().toLocalDate());

        Map<Integer, Achievement.AchievementType> streakMilestones = Map.of(
                7, Achievement.AchievementType.STREAK_7_DAYS,
                30, Achievement.AchievementType.STREAK_30_DAYS,
                100, Achievement.AchievementType.STREAK_100_DAYS,
                365, Achievement.AchievementType.STREAK_365_DAYS
        );

        for (Map.Entry<Integer, Achievement.AchievementType> entry : streakMilestones.entrySet()) {
            if (previousStreak < entry.getKey() &&
                currentStreak >= entry.getKey() &&
                !existing.contains(entry.getValue())) {
                achievements.add(awardAchievement(
                        userId,
                        entry.getValue(),
                        String.format("%d Day Streak", entry.getKey()),
                        String.format("Worked out %d days in a row!", entry.getKey()),
                        "🔥",
                        "#ff1493",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("streak_days", entry.getKey())
                ));
            }
        }

        return achievements;
    }

    /**
     * Check time-based achievements (early bird, night owl, weekend warrior).
     */
    private List<Achievement> checkTimeBasedAchievements(UUID userId, Activity activity,
                                                         ActivityProgress progress,
                                                         Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        LocalTime startTime = activity.getStartedAt().toLocalTime();

        // Early bird (before 6am)
        if (startTime.isBefore(LocalTime.of(6, 0)) && !existing.contains(Achievement.AchievementType.EARLY_BIRD)) {
            long previousEarlyActivities = progress.previousActivitiesStartingBefore(LocalTime.of(6, 0));
            long currentEarlyActivities = progress.currentActivitiesStartingBefore(LocalTime.of(6, 0));
            if (previousEarlyActivities < 5 && currentEarlyActivities >= 5) {
                achievements.add(awardAchievement(
                        userId,
                        Achievement.AchievementType.EARLY_BIRD,
                        "Early Bird",
                        "Completed 5+ activities before 6am!",
                        "🌅",
                        "#ccff00",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("early_activities", currentEarlyActivities)
                ));
            }
        }

        // Night owl (after 10pm)
        if (startTime.isAfter(LocalTime.of(22, 0)) && !existing.contains(Achievement.AchievementType.NIGHT_OWL)) {
            long previousLateActivities = progress.previousActivitiesStartingAfter(LocalTime.of(22, 0));
            long currentLateActivities = progress.currentActivitiesStartingAfter(LocalTime.of(22, 0));
            if (previousLateActivities < 5 && currentLateActivities >= 5) {
                achievements.add(awardAchievement(
                        userId,
                        Achievement.AchievementType.NIGHT_OWL,
                        "Night Owl",
                        "Completed 5+ activities after 10pm!",
                        "🦉",
                        "#9370db",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("late_activities", currentLateActivities)
                ));
            }
        }

        return achievements;
    }

    /**
     * Check elevation achievements.
     */
    private List<Achievement> checkElevationAchievements(UUID userId, Activity activity,
                                                         ActivityProgress progress,
                                                         Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        // Single activity elevation
        if (activity.getElevationGain() != null &&
            activity.getElevationGain().compareTo(BigDecimal.valueOf(1000)) >= 0 &&
            !progress.previousHasElevationGainAtLeast(BigDecimal.valueOf(1000)) &&
            !existing.contains(Achievement.AchievementType.MOUNTAINEER_1000M)) {

            achievements.add(awardAchievement(
                    userId,
                    Achievement.AchievementType.MOUNTAINEER_1000M,
                    "Mountaineer",
                    "Climbed 1000m+ in a single activity!",
                    "⛰️",
                    "#8b4513",
                    activity.getId(),
                    activity.getEndedAt(),
                    Map.of("elevation_gain", activity.getElevationGain())
            ));
        }

        // Total elevation milestones
        double previousElevation = progress.previousElevationMeters().doubleValue();
        double currentElevation = progress.currentElevationMeters().doubleValue();

        if (previousElevation < 5000 &&
            currentElevation >= 5000 &&
            !existing.contains(Achievement.AchievementType.MOUNTAINEER_5000M)) {
            achievements.add(awardAchievement(
                    userId,
                    Achievement.AchievementType.MOUNTAINEER_5000M,
                    "Mountain Conqueror",
                    "Climbed 5000m total elevation!",
                    "🏔️",
                    "#4169e1",
                    activity.getId(),
                    activity.getEndedAt(),
                    Map.of("total_elevation", currentElevation)
            ));
        }

        if (previousElevation < 10000 &&
            currentElevation >= 10000 &&
            !existing.contains(Achievement.AchievementType.MOUNTAINEER_10000M)) {
            achievements.add(awardAchievement(
                    userId,
                    Achievement.AchievementType.MOUNTAINEER_10000M,
                    "Summit Master",
                    "Climbed 10000m total elevation!",
                    "🗻",
                    "#1e90ff",
                    activity.getId(),
                    activity.getEndedAt(),
                    Map.of("total_elevation", currentElevation)
            ));
        }

        return achievements;
    }

    /**
     * Check variety achievements.
     */
    private List<Achievement> checkVarietyAchievements(UUID userId, Activity activity,
                                                       ActivityProgress progress,
                                                       Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        long previousDistinctActivityTypes = progress.previousDistinctActivityTypes();
        long currentDistinctActivityTypes = progress.currentDistinctActivityTypes();

        if (previousDistinctActivityTypes < 3 &&
            currentDistinctActivityTypes >= 3 &&
            !existing.contains(Achievement.AchievementType.VARIETY_SEEKER)) {
            achievements.add(awardAchievement(
                    userId,
                    Achievement.AchievementType.VARIETY_SEEKER,
                    "Variety Seeker",
                    "Tried 3+ different activity types!",
                    "🌈",
                    "#ff69b4",
                    activity.getId(),
                    activity.getEndedAt(),
                    Map.of("activity_types", currentDistinctActivityTypes)
            ));
        }

        return achievements;
    }

    /**
     * Check speed achievements.
     */
    private List<Achievement> checkSpeedAchievements(UUID userId, Activity activity,
                                                     ActivityProgress progress,
                                                     Set<Achievement.AchievementType> existing) {
        List<Achievement> achievements = new ArrayList<>();

        if (activity.getMetrics() != null && activity.getMetrics().getMaxSpeed() != null) {
            // maxSpeed is already in km/h from FitParser
            double maxSpeedKmh = activity.getMetrics().getMaxSpeed().doubleValue();

            if (maxSpeedKmh >= 40 &&
                !progress.previousHasMaxSpeedAtLeast(BigDecimal.valueOf(40)) &&
                !existing.contains(Achievement.AchievementType.SPEED_DEMON)) {
                achievements.add(awardAchievement(
                        userId,
                        Achievement.AchievementType.SPEED_DEMON,
                        "Speed Demon",
                        "Reached 40+ km/h!",
                        "⚡",
                        "#ffd700",
                        activity.getId(),
                        activity.getEndedAt(),
                        Map.of("max_speed_kmh", maxSpeedKmh)
                ));
            }
        }

        return achievements;
    }

    /**
     * Award an achievement to a user.
     */
    private Achievement awardAchievement(UUID userId, Achievement.AchievementType achievementType,
                                        String name, String description, String icon, String color,
                                        UUID activityId, LocalDateTime earnedAt,
                                        Map<String, Object> metadata) {
        Achievement achievement = Achievement.builder()
                .userId(userId)
                .achievementType(achievementType)
                .name(name)
                .description(description)
                .badgeIcon(icon)
                .badgeColor(color)
                .earnedAt(earnedAt != null ? earnedAt : LocalDateTime.now())
                .activityId(activityId)
                .metadata(metadata)
                .build();

        achievementRepository.save(achievement);
        log.info("Achievement earned: {} by user {}", name, userId);
        return achievement;
    }

    /**
     * Get emoji for activity type.
     */
    private String getActivityEmoji(String activityType) {
        return switch (activityType.toUpperCase()) {
            case "RUN" -> "🏃";
            case "RIDE" -> "🚴";
            case "HIKE" -> "🥾";
            case "WALK" -> "🚶";
            case "SWIM" -> "🏊";
            default -> "💪";
        };
    }

    /**
     * Get all achievements for a user.
     */
    @Transactional(readOnly = true)
    public List<Achievement> getUserAchievements(UUID userId) {
        return achievementRepository.findByUserIdOrderByEarnedAtDesc(userId);
    }

    /**
     * Get achievement count for a user.
     */
    @Transactional(readOnly = true)
    public long getAchievementCount(UUID userId) {
        return achievementRepository.countByUserId(userId);
    }

    private int calculateStreak(Set<LocalDate> activityDates, LocalDate anchorDate) {
        if (activityDates.isEmpty() || !activityDates.contains(anchorDate)) {
            return 0;
        }

        LocalDate checkDate = anchorDate;
        int streak = 0;

        for (int i = 0; i < 365; i++) {
            boolean hasActivity = activityDates.contains(checkDate);

            if (hasActivity) {
                streak++;
                checkDate = checkDate.minusDays(1);
            } else {
                if (streak > 0 && i > 0) {
                    checkDate = checkDate.minusDays(1);
                    continue;
                }
                break;
            }
        }

        return streak;
    }

    private record ActivityProgress(
            List<Activity> previousActivities,
            List<Activity> currentActivities
    ) {
        private static ActivityProgress fromHistory(List<Activity> activityHistory, Activity currentActivity) {
            int currentIndex = -1;
            for (int i = 0; i < activityHistory.size(); i++) {
                if (Objects.equals(activityHistory.get(i).getId(), currentActivity.getId())) {
                    currentIndex = i;
                    break;
                }
            }

            if (currentIndex < 0) {
                throw new IllegalStateException("Current activity missing from chronological history: " + currentActivity.getId());
            }

            return new ActivityProgress(
                    List.copyOf(activityHistory.subList(0, currentIndex)),
                    List.copyOf(activityHistory.subList(0, currentIndex + 1))
            );
        }

        long previousActivityCount() {
            return previousActivities.size();
        }

        long currentActivityCount() {
            return currentActivities.size();
        }

        long previousActivityTypeCount(Activity.ActivityType type) {
            return previousActivities.stream().filter(activity -> activity.getActivityType() == type).count();
        }

        long currentActivityTypeCount(Activity.ActivityType type) {
            return currentActivities.stream().filter(activity -> activity.getActivityType() == type).count();
        }

        BigDecimal previousDistanceMeters() {
            return sumDistance(previousActivities);
        }

        BigDecimal currentDistanceMeters() {
            return sumDistance(currentActivities);
        }

        BigDecimal previousElevationMeters() {
            return sumElevation(previousActivities);
        }

        BigDecimal currentElevationMeters() {
            return sumElevation(currentActivities);
        }

        boolean previousHasElevationGainAtLeast(BigDecimal threshold) {
            return previousActivities.stream()
                    .map(Activity::getElevationGain)
                    .filter(Objects::nonNull)
                    .anyMatch(elevation -> elevation.compareTo(threshold) >= 0);
        }

        long previousDistinctActivityTypes() {
            return previousActivities.stream().map(Activity::getActivityType).distinct().count();
        }

        long currentDistinctActivityTypes() {
            return currentActivities.stream().map(Activity::getActivityType).distinct().count();
        }

        long previousActivitiesStartingBefore(LocalTime time) {
            return previousActivities.stream()
                    .filter(activity -> activity.getStartedAt() != null)
                    .filter(activity -> activity.getStartedAt().toLocalTime().isBefore(time))
                    .count();
        }

        long currentActivitiesStartingBefore(LocalTime time) {
            return currentActivities.stream()
                    .filter(activity -> activity.getStartedAt() != null)
                    .filter(activity -> activity.getStartedAt().toLocalTime().isBefore(time))
                    .count();
        }

        long previousActivitiesStartingAfter(LocalTime time) {
            return previousActivities.stream()
                    .filter(activity -> activity.getStartedAt() != null)
                    .filter(activity -> activity.getStartedAt().toLocalTime().isAfter(time))
                    .count();
        }

        long currentActivitiesStartingAfter(LocalTime time) {
            return currentActivities.stream()
                    .filter(activity -> activity.getStartedAt() != null)
                    .filter(activity -> activity.getStartedAt().toLocalTime().isAfter(time))
                    .count();
        }

        Set<LocalDate> previousActivityDates() {
            return collectDates(previousActivities);
        }

        Set<LocalDate> currentActivityDates() {
            return collectDates(currentActivities);
        }

        boolean previousHasMaxSpeedAtLeast(BigDecimal thresholdKmh) {
            return previousActivities.stream()
                    .map(Activity::getMetrics)
                    .filter(Objects::nonNull)
                    .map(metrics -> metrics.getMaxSpeed())
                    .filter(Objects::nonNull)
                    .anyMatch(speed -> speed.compareTo(thresholdKmh) >= 0);
        }

        private static BigDecimal sumDistance(List<Activity> activities) {
            return activities.stream()
                    .map(Activity::getTotalDistance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private static BigDecimal sumElevation(List<Activity> activities) {
            return activities.stream()
                    .map(Activity::getElevationGain)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private static Set<LocalDate> collectDates(List<Activity> activities) {
            Set<LocalDate> dates = new HashSet<>();
            for (Activity activity : activities) {
                if (activity.getStartedAt() != null) {
                    dates.add(activity.getStartedAt().toLocalDate());
                }
            }
            return dates;
        }
    }
}
