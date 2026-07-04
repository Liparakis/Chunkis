package io.liparakis.chunkis.migration.state;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Process-local registry of dimensions whose CIS data is authoritative.
 */
public final class MigrationStateServiceHolder {

    /**
     * Process-local dimension identifiers whose CIS storage should be treated as authoritative.
     */
    private static final Set<String> AUTHORITATIVE_DIMENSIONS = ConcurrentHashMap.newKeySet();

    /**
     * Private constructor to prevent utility class instantiation.
     */
    private MigrationStateServiceHolder() {
    }

    /**
     * Marks a dimension as CIS-authoritative for the lifetime of the current process.
     *
     * @param worldKey dimension key to record
     */
    public static void markAuthoritative(final RegistryKey<World> worldKey) {
        if (worldKey != null) {
            AUTHORITATIVE_DIMENSIONS.add(worldKey.getValue()
                    .toString());
        }
    }

    /**
     * Returns whether the given dimension has been marked CIS-authoritative in this process.
     *
     * @param worldKey dimension key to query
     * @return {@code true} if vanilla storage should be blocked for the dimension
     */
    public static boolean isAuthoritative(final RegistryKey<World> worldKey) {
        return worldKey != null && AUTHORITATIVE_DIMENSIONS.contains(worldKey.getValue()
                .toString());
    }

    /**
     * Clears all authoritative dimension markers.
     */
    public static void clear() {
        AUTHORITATIVE_DIMENSIONS.clear();
    }
}
