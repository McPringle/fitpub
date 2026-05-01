package net.javahippie.fitpub.service;

import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.FollowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@DisplayName("ProfileAccessService Tests")
class ProfileAccessServiceTest {

    @Test
    @DisplayName("PUBLIC profile should be visible anonymously")
    void publicProfileShouldBeVisibleAnonymously() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(Optional.empty(), lookupCount);
        User owner = user("owner-public", User.ProfileVisibility.PUBLIC);

        assertTrue(service.canViewProfile(owner, null));
        assertEquals(0, lookupCount.get());
    }

    @Test
    @DisplayName("PUBLIC profile should be visible to another authenticated user")
    void publicProfileShouldBeVisibleToAnotherAuthenticatedUser() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(Optional.empty(), lookupCount);
        User owner = user("owner-public-auth", User.ProfileVisibility.PUBLIC);
        User viewer = user("viewer-public-auth", User.ProfileVisibility.PUBLIC);

        assertTrue(service.canViewProfile(owner, viewer));
        assertEquals(0, lookupCount.get());
    }

    @Test
    @DisplayName("FOLLOWERS profile should be forbidden anonymously")
    void followersProfileShouldBeForbiddenAnonymously() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());
        User owner = user("owner-followers-anon", User.ProfileVisibility.FOLLOWERS);

        assertFalse(service.canViewProfile(owner, null));
    }

    @Test
    @DisplayName("FOLLOWERS profile should be forbidden to non followers")
    void followersProfileShouldBeForbiddenToNonFollowers() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(Optional.empty(), lookupCount);
        User owner = user("owner-followers-nonf", User.ProfileVisibility.FOLLOWERS);
        User viewer = user("viewer-followers-nonf", User.ProfileVisibility.PUBLIC);

        assertFalse(service.canViewProfile(owner, viewer));
        assertEquals(1, lookupCount.get());
    }

    @Test
    @DisplayName("FOLLOWERS profile should be visible to accepted followers")
    void followersProfileShouldBeVisibleToAcceptedFollowers() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(
            Optional.of(Follow.builder().status(Follow.FollowStatus.ACCEPTED).build()),
            lookupCount
        );
        User owner = user("owner-followers-accepted", User.ProfileVisibility.FOLLOWERS);
        User viewer = user("viewer-followers-accepted", User.ProfileVisibility.PUBLIC);

        assertTrue(service.canViewProfile(owner, viewer));
        assertEquals(1, lookupCount.get());
    }

    @Test
    @DisplayName("FOLLOWERS profile should be forbidden to pending followers")
    void followersProfileShouldBeForbiddenToPendingFollowers() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(
            Optional.of(Follow.builder().status(Follow.FollowStatus.PENDING).build()),
            lookupCount
        );
        User owner = user("owner-followers-pending", User.ProfileVisibility.FOLLOWERS);
        User viewer = user("viewer-followers-pending", User.ProfileVisibility.PUBLIC);

        assertFalse(service.canViewProfile(owner, viewer));
        assertEquals(1, lookupCount.get());
    }

    @Test
    @DisplayName("PRIVATE profile should be forbidden anonymously")
    void privateProfileShouldBeForbiddenAnonymously() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());
        User owner = user("owner-private-anon", User.ProfileVisibility.PRIVATE);

        assertFalse(service.canViewProfile(owner, null));
    }

    @Test
    @DisplayName("PRIVATE profile should be forbidden to another authenticated user")
    void privateProfileShouldBeForbiddenToAnotherAuthenticatedUser() {
        AtomicInteger lookupCount = new AtomicInteger();
        ProfileAccessService service = createService(Optional.empty(), lookupCount);
        User owner = user("owner-private-other", User.ProfileVisibility.PRIVATE);
        User viewer = user("viewer-private-other", User.ProfileVisibility.PUBLIC);

        assertFalse(service.canViewProfile(owner, viewer));
        assertEquals(0, lookupCount.get());
    }

    @Test
    @DisplayName("Owner should always be able to view own profile")
    void ownerShouldAlwaysBeAbleToViewOwnProfile() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());

        for (User.ProfileVisibility visibility : User.ProfileVisibility.values()) {
            User owner = user("self-" + visibility.name().toLowerCase(), visibility);
            assertTrue(service.canViewProfile(owner, owner));
        }
    }

    @Test
    @DisplayName("Require profile access should throw forbidden with followers message")
    void requireProfileAccessShouldThrowForbiddenWithFollowersMessage() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());
        User owner = user("owner-followers-msg", User.ProfileVisibility.FOLLOWERS);
        User viewer = user("viewer-followers-msg", User.ProfileVisibility.PUBLIC);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.requireProfileAccess(owner, viewer)
        );

        assertEquals(FORBIDDEN, exception.getStatusCode());
        assertEquals("This profile is only visible to followers.", exception.getReason());
    }

    @Test
    @DisplayName("Require profile access should throw forbidden with private message")
    void requireProfileAccessShouldThrowForbiddenWithPrivateMessage() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());
        User owner = user("owner-private-msg", User.ProfileVisibility.PRIVATE);
        User viewer = user("viewer-private-msg", User.ProfileVisibility.PUBLIC);

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.requireProfileAccess(owner, viewer)
        );

        assertEquals(FORBIDDEN, exception.getStatusCode());
        assertEquals("This profile is private.", exception.getReason());
    }

    @Test
    @DisplayName("Require profile access should allow visible profiles")
    void requireProfileAccessShouldAllowVisibleProfiles() {
        ProfileAccessService service = createService(Optional.empty(), new AtomicInteger());
        User owner = user("owner-public-visible", User.ProfileVisibility.PUBLIC);

        assertDoesNotThrow(() -> service.requireProfileAccess(owner, null));
    }

    private ProfileAccessService createService(Optional<Follow> followLookupResult, AtomicInteger lookupCount) {
        FollowRepository repository = (FollowRepository) Proxy.newProxyInstance(
            FollowRepository.class.getClassLoader(),
            new Class[]{FollowRepository.class},
            (proxy, method, args) -> {
                if ("findByFollowerIdAndFollowingActorUri".equals(method.getName())) {
                    lookupCount.incrementAndGet();
                    return followLookupResult;
                }

                throw new UnsupportedOperationException("Unexpected repository method: " + method.getName());
            }
        );

        ProfileAccessService service = new ProfileAccessService(repository);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
        return service;
    }

    private User user(String username, User.ProfileVisibility visibility) {
        return User.builder()
            .id(UUID.randomUUID())
            .username(username)
            .email(username + "@example.com")
            .passwordHash("hash")
            .publicKey("pub")
            .privateKey("priv")
            .profileVisibility(visibility)
            .build();
    }
}
