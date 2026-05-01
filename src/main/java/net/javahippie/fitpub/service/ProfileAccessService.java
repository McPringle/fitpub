package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.FollowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Central access policy for profile visibility checks.
 */
@Service
@RequiredArgsConstructor
public class ProfileAccessService {

    private final FollowRepository followRepository;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    public boolean canViewProfile(User profileOwner, User viewer) {
        if (viewer != null && viewer.getId().equals(profileOwner.getId())) {
            return true;
        }

        User.ProfileVisibility visibility = profileOwner.getProfileVisibility() != null
            ? profileOwner.getProfileVisibility()
            : User.ProfileVisibility.PUBLIC;

        if (visibility == User.ProfileVisibility.PUBLIC) {
            return true;
        }

        if (visibility == User.ProfileVisibility.PRIVATE || viewer == null) {
            return false;
        }

        String actorUri = profileOwner.getActorUri(baseUrl);
        return followRepository.findByFollowerIdAndFollowingActorUri(viewer.getId(), actorUri)
            .filter(follow -> follow.getStatus() == Follow.FollowStatus.ACCEPTED)
            .isPresent();
    }

    public String getAccessDeniedMessage(User profileOwner) {
        return profileOwner.getProfileVisibility() == User.ProfileVisibility.FOLLOWERS
            ? "This profile is only visible to followers."
            : "This profile is private.";
    }

    public void requireProfileAccess(User profileOwner, User viewer) {
        if (!canViewProfile(profileOwner, viewer)) {
            throw new ResponseStatusException(FORBIDDEN, getAccessDeniedMessage(profileOwner));
        }
    }
}
