package net.javahippie.fitpub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.config.KomootSupport;
import net.javahippie.fitpub.model.dto.KomootActivityImportRequest;
import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootImportExecutionResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.service.KomootImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API for loading and importing Komoot activities.
 */
@RestController
@RequestMapping("/api/komoot-import")
@RequiredArgsConstructor
@Slf4j
public class KomootImportController {

    private final KomootSupport komootSupport;
    private final KomootImportService komootImportService;
    private final UserRepository userRepository;

    @PostMapping("/activities")
    public ResponseEntity<KomootActivitiesResponse> listActivities(
            @Valid @RequestBody KomootImportRequest request,
            Authentication authentication
    ) {
        ensureKomootSupportEnabled();

        UUID fitPubUserId = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"))
                .getId();

        log.info("User {} requested Komoot activity preview for Komoot ID {}",
                authentication.getName(), request.getUserId());
        KomootActivitiesResponse response = komootImportService.fetchCompletedActivities(request, fitPubUserId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/activities/import")
    public ResponseEntity<KomootImportExecutionResponse> importActivity(
            @Valid @RequestBody KomootActivityImportRequest request,
            Authentication authentication
    ) {
        ensureKomootSupportEnabled();

        UUID fitPubUserId = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"))
                .getId();

        log.info("User {} requested Komoot import for activity {}",
                authentication.getName(), request.getActivityId());

        KomootImportExecutionResponse response = komootImportService.importActivity(
                request,
                fitPubUserId
        );
        return ResponseEntity.ok(response);
    }

    private void ensureKomootSupportEnabled() {
        if (!komootSupport.isEnabled()) {
            throw new KomootSupportDisabledException();
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid request")
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(KomootSupportDisabledException.class)
    public ResponseEntity<ErrorResponse> handleKomootSupportDisabled(KomootSupportDisabledException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Komoot support is disabled."));
    }

    record ErrorResponse(String error) {}

    static class KomootSupportDisabledException extends RuntimeException {
    }
}
