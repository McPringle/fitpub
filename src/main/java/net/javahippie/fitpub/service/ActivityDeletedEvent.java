package net.javahippie.fitpub.service;

import java.time.LocalDate;
import java.util.UUID;

public record ActivityDeletedEvent(UUID userId, LocalDate activityDate) {
}
