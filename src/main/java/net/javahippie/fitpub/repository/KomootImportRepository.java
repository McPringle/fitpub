package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.KomootImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KomootImportRepository extends JpaRepository<KomootImport, UUID> {

    interface KomootImportLinkProjection {
        UUID getActivityId();
        Long getKomootActivityId();
    }

    @Query("SELECT k.komootActivityId FROM KomootImport k WHERE k.userId = :userId")
    List<Long> findImportedKomootActivityIdsByUserId(@Param("userId") UUID userId);

    Optional<KomootImport> findByUserIdAndKomootActivityId(UUID userId, Long komootActivityId);

    @Query("SELECT k.activityId AS activityId, k.komootActivityId AS komootActivityId " +
           "FROM KomootImport k " +
           "WHERE k.userId = :userId AND k.komootActivityId IN :komootActivityIds")
    List<KomootImportLinkProjection> findKomootImportLinksByUserIdAndKomootActivityIdIn(
            @Param("userId") UUID userId,
            @Param("komootActivityIds") List<Long> komootActivityIds
    );
}
