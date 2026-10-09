package dev.keystone.config;

import java.util.Set;

/**
 * How a user's copy of a shipped YAML file is brought up to date with the jar's default.
 *
 * <p>Modelled on CraftEngine's config upgrade (a version key, the jar file as template, the user's
 * values kept), with two deliberate differences:
 *
 * <ul>
 *   <li>A value whose type differs from the default is <b>kept</b>, and reported, instead of being
 *       replaced by the default. Lang files legitimately turn a string into a list of lines.
 *   <li>Keys the default no longer has are kept unless {@code deleteRemovedNodes} is set;
 *       CraftEngine deletes them. Deleting is only safe once every user-owned map is listed in
 *       {@code dynamicRoutes}.
 * </ul>
 *
 * @param updateComments copy non-empty comments from the default onto the user's existing keys
 * @param deleteRemovedNodes remove user keys the default no longer has (outside dynamic routes)
 * @param dynamicRoutes dotted paths whose children belong to the user: never merged into or
 *     cleaned, only created when missing entirely
 * @param reorder put keys back into the default file's order (user-only keys last)
 */
public record UpgradePolicy(
        boolean updateComments,
        boolean deleteRemovedNodes,
        Set<String> dynamicRoutes,
        boolean reorder) {

    /** Settings files such as {@code config.yml}. */
    public static final UpgradePolicy CONFIG = new UpgradePolicy(true, false, Set.of(), true);

    /** Lang files: add missing keys, never touch what the user wrote. */
    public static final UpgradePolicy LANG = new UpgradePolicy(false, false, Set.of(), false);

    public UpgradePolicy {
        dynamicRoutes = Set.copyOf(dynamicRoutes);
    }

    /** {@link #CONFIG} with these user-owned sections. */
    public static UpgradePolicy config(String... dynamicRoutes) {
        return new UpgradePolicy(true, false, Set.of(dynamicRoutes), true);
    }

    /** A copy that deletes keys the default no longer has, outside {@link #dynamicRoutes}. */
    public UpgradePolicy deletingRemovedNodes() {
        return new UpgradePolicy(updateComments, true, dynamicRoutes, reorder);
    }
}
