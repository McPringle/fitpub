package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.FederationInbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FederationInboxRepository extends JpaRepository<FederationInbox, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fi from FederationInbox fi where fi.id = :id")
    Optional<FederationInbox> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<FederationInbox> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscReceivedAtAsc(
        FederationInbox.Status status,
        LocalDateTime now,
        Pageable pageable
    );

    List<FederationInbox> findByStatusAndProcessingStartedAtLessThanEqualOrderByProcessingStartedAtAsc(
        FederationInbox.Status status,
        LocalDateTime threshold,
        Pageable pageable
    );
}
