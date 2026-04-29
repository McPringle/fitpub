package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AchievementRepository extends JpaRepository<Achievement, UUID> {

    /**
     * Find all achievements for a user, ordered by most recent first.
     */
    List<Achievement> findByUserIdOrderByEarnedAtDesc(UUID userId);

    /**
     * Find a specific achievement by user and type.
     */
    Optional<Achievement> findByUserIdAndAchievementType(
            UUID userId,
            Achievement.AchievementType achievementType
    );

    /**
     * Check if a user has earned a specific achievement.
     */
    boolean existsByUserIdAndAchievementType(
            UUID userId,
            Achievement.AchievementType achievementType
    );

    /**
     * Get count of achievements earned by a user.
     */
    long countByUserId(UUID userId);

    /**
     * Delete all achievements for a user.
     */
    @Modifying
    void deleteByUserId(UUID userId);

    /**
     * Get count of achievements earned by a user in a date range.
     */
    @Query("SELECT COUNT(a) FROM Achievement a " +
           "WHERE a.userId = :userId " +
           "AND a.earnedAt >= :startDate " +
           "AND a.earnedAt < :endDate")
    long countByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    /**
     * Count achievements whose triggering activity started within a date range.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM achievements ach
            JOIN activities act ON act.id = ach.activity_id
            WHERE ach.user_id = :userId
              AND act.started_at >= :startDate
              AND act.started_at < :endDate
            """, nativeQuery = true)
    long countByUserIdAndActivityStartedDateRange(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    /**
     * Find recent achievements for a user.
     */
    @Query("SELECT a FROM Achievement a " +
           "WHERE a.userId = :userId " +
           "ORDER BY a.earnedAt DESC " +
           "LIMIT :limit")
    List<Achievement> findRecentByUserId(@Param("userId") UUID userId, @Param("limit") int limit);
}
