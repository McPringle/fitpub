package net.javahippie.fitpub.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared {@code @context} builders for outbound ActivityPub objects.
 *
 * <p>Centralises the JSON-LD context shape so all the federation code paths
 * (Create wrappers in {@code FederationService}, the standalone Note returned
 * by {@code ActivityPubController#getActivity}) use the same one.
 *
 * <p>The "extended" context declares the interaction-policy extension fields
 * ({@code interactionPolicy}, {@code canQuote}, {@code automaticApproval},
 * {@code manualApproval}) under the {@code gts:} prefix
 * ({@code https://gotosocial.org/ns#}). This matches Mastodon's
 * {@code app/helpers/context_helper.rb} ("interaction_policies" extension),
 * which declares these fields in the GoToSocial namespace rather than
 * Mastodon's own {@code toot:} namespace. Without these declarations,
 * Mastodon ignores any {@code interactionPolicy} field on the inner object
 * and falls back to its default policy (which currently denies quotes for
 * cross-server posts).
 *
 * <p>This file deliberately doesn't try to enumerate every possible Mastodon
 * extension — only the ones we actively use. Add fields here as we adopt them.
 */
public final class ActivityPubContexts {

    /** The well-known ActivityStreams "Public" audience URI. */
    public static final String PUBLIC_AUDIENCE = "https://www.w3.org/ns/activitystreams#Public";

    private ActivityPubContexts() {
    }

    /**
     * Returns the extended JSON-LD {@code @context} value for outbound objects
     * that carry interaction-policy declarations. Shape:
     *
     * <pre>
     * [
     *   "https://www.w3.org/ns/activitystreams",
     *   {
     *     "gts": "https://gotosocial.org/ns#",
     *     "interactionPolicy":  { "@id": "gts:interactionPolicy",  "@type": "@id" },
     *     "canQuote":           { "@id": "gts:canQuote",           "@type": "@id" },
     *     "automaticApproval":  { "@id": "gts:automaticApproval",  "@type": "@id" },
     *     "manualApproval":     { "@id": "gts:manualApproval",     "@type": "@id" }
     *   }
     * ]
     * </pre>
     *
     * <p>The {@code gts:} prefix is the GoToSocial namespace
     * ({@code https://gotosocial.org/ns#}). Mastodon emits and consumes
     * exactly this shape (see {@code app/helpers/context_helper.rb} in the
     * Mastodon source, "interaction_policies" extension), so a Mastodon
     * receiver compacting our object with its own context will recognise the
     * field names and apply the policy.
     */
    public static List<Object> extendedContext() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("gts", "https://gotosocial.org/ns#");
        extensions.put("interactionPolicy", typedRef("gts:interactionPolicy"));
        extensions.put("canQuote",          typedRef("gts:canQuote"));
        extensions.put("automaticApproval", typedRef("gts:automaticApproval"));
        extensions.put("manualApproval",    typedRef("gts:manualApproval"));
        return List.of(
            "https://www.w3.org/ns/activitystreams",
            extensions
        );
    }

    /**
     * Build an interaction policy that allows the given audience to quote
     * automatically (no manual approval required). The audience should be a
     * URI like {@link #PUBLIC_AUDIENCE} or an actor's followers collection.
     *
     * <p>Shape:
     * <pre>
     * {
     *   "canQuote": {
     *     "automaticApproval": ["https://www.w3.org/ns/activitystreams#Public"]
     *   }
     * }
     * </pre>
     */
    public static Map<String, Object> quotePolicyAllowing(String audienceUri) {
        Map<String, Object> canQuote = new LinkedHashMap<>();
        canQuote.put("automaticApproval", List.of(audienceUri));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("canQuote", canQuote);
        return policy;
    }

    private static Map<String, String> typedRef(String id) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("@id", id);
        m.put("@type", "@id");
        return m;
    }
}
