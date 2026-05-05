package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.FederationInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FederationInboxRepository extends JpaRepository<FederationInbox, UUID> {
}
