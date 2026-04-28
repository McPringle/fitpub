package net.javahippie.fitpub.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.dto.KomootActivitiesResponse;
import net.javahippie.fitpub.model.dto.KomootImportRequest;
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

/**
 * REST API for previewing completed Komoot activities.
 */
@RestController
@RequestMapping("/api/komoot-import")
@RequiredArgsConstructor
@Slf4j
public class KomootImportController {

    private final KomootImportService komootImportService;

    @PostMapping("/activities")
    public ResponseEntity<KomootActivitiesResponse> listActivities(
            @Valid @RequestBody KomootImportRequest request,
            Authentication authentication
    ) {
        log.info("User {} requested Komoot activity preview for Komoot ID {}",
                authentication.getName(), request.userId());
        KomootActivitiesResponse response = komootImportService.fetchCompletedActivities(request);
        return ResponseEntity.ok(response);
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

    record ErrorResponse(String error) {}
}
