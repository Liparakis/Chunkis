package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Fabric implementation of {@link BlockRegistryAdapter}.
 *
 * <p>This adapter maps Minecraft blocks to registry ID strings and registry ID
 * strings back to blocks. Small concurrent caches are used to avoid repeated
 * registry lookups and string allocation during CIS encode/decode paths.</p>
 *
 * <p>Fast paths are provided for {@link Blocks#AIR}, since air is usually the
 * most common block state in chunk storage.</p>
 *
 * <p><b>Thread safety:</b> cache access uses {@link ConcurrentHashMap}. The
 * adapter stores only registry constants and ID strings, not world or chunk
 * references.</p>
 *
 * @author Liparakis
 * @version 1.2
 */
public final class FabricBlockRegistryAdapter implements BlockRegistryAdapter<Block> {

    /**
     * Maximum entries retained per lookup cache.
     *
     * <p>Minecraft's block registry is finite, but modded environments can still
     * produce many IDs. This cap prevents accidental unbounded growth from malformed
     * or unexpected serialized input.</p>
     */
    private static final int MAX_CACHE_SIZE = 1024;

    /**
     * Common AIR block fast-path.
     */
    private static final Block AIR_BLOCK = Blocks.AIR;

    /**
     * Common AIR ID fast-path.
     */
    private static final String AIR_ID = "minecraft:air";

    /**
     * Parsed AIR identifier used as a fallback for malformed IDs.
     */
    private static final Identifier AIR_IDENTIFIER = Identifier.of("minecraft", "air");

    /**
     * Block instance to registry ID cache.
     */
    private final Map<Block, String> blockToIdCache = new ConcurrentHashMap<>(256);

    /**
     * Registry ID string to block instance cache.
     *
     * <p>This also makes a separate string-to-identifier cache unnecessary:
     * parsing only happens on block-cache misses.</p>
     */
    private final Map<String, Block> idToBlockCache = new ConcurrentHashMap<>(256);

    /**
     * Resolves a block's registry ID string.
     *
     * <p>The string is interned because registry IDs are repeated heavily in CIS
     * metadata and mappings. Interning is safe here because the registry block set
     * is bounded compared to arbitrary user strings.</p>
     *
     * @param block block to resolve
     * @return interned registry ID string
     */
    private static String resolveBlockId(final Block block) {
        return Registries.BLOCK.getId(block).toString().intern();
    }

    /**
     * Resolves a registry ID string to a block.
     *
     * @param id registry ID string
     * @return resolved block, or air if invalid/unknown
     */
    private static Block resolveBlock(final String id) {
        final Identifier identifier = parseIdentifierOrAir(id);
        return Registries.BLOCK.get(identifier);
    }

    /**
     * Parses an identifier, falling back to air when malformed.
     *
     * @param id raw ID string
     * @return parsed identifier, or air identifier
     */
    private static Identifier parseIdentifierOrAir(final String id) {
        final Identifier parsed = Identifier.tryParse(id);
        return parsed != null ? parsed : AIR_IDENTIFIER;
    }

    /**
     * Clears a cache if it has grown beyond the configured cap.
     *
     * <p>The eviction strategy is intentionally simple. The registry key space is
     * small and stable during normal play, so full clear is cheaper than bringing
     * in an LRU dependency for this adapter.</p>
     *
     * @param cache cache to check
     */
    private static void evictIfNeeded(final Map<?, ?> cache) {
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
    }

    /**
     * Returns whether an ID string is unusable.
     *
     * @param id ID string
     * @return {@code true} when null or empty
     */
    private static boolean isInvalidId(final String id) {
        return id == null || id.isEmpty();
    }

    /**
     * Returns the registry ID string for a block.
     *
     * @param block block to identify
     * @return registry ID, for example {@code minecraft:stone}
     */
    @Override
    public String getId(final Block block) {
        Objects.requireNonNull(block, "block");

        if (block == AIR_BLOCK) {
            return AIR_ID;
        }

        final String cached = blockToIdCache.get(block);

        if (cached != null) {
            return cached;
        }

        final String resolved = resolveBlockId(block);
        cacheBlockId(block, resolved);

        return resolved;
    }

    /**
     * Returns the block for a registry ID string.
     *
     * <p>Malformed, null, empty, or unknown IDs safely resolve to air.</p>
     *
     * @param id registry ID string, for example {@code minecraft:stone}
     * @return resolved block, or air when invalid
     */
    @Override
    public Block getBlock(final String id) {
        if (isInvalidId(id) || AIR_ID.equals(id)) {
            return AIR_BLOCK;
        }

        final Block cached = idToBlockCache.get(id);

        if (cached != null) {
            return cached;
        }

        final Block resolved = resolveBlock(id);
        cacheBlock(id, resolved);

        return resolved;
    }

    /**
     * Returns the canonical air block.
     *
     * @return air block
     */
    @Override
    public Block getAir() {
        return AIR_BLOCK;
    }

    /**
     * Returns all currently registered blocks.
     *
     * <p>This intentionally does not cache the result. Some mod/plugin setups may
     * construct adapters during registration phases, and caching too early could
     * expose a stale block list.</p>
     *
     * @return collection snapshot of registered blocks
     */
    @Override
    public Collection<Block> getRegisteredBlocks() {
        return Registries.BLOCK.stream().toList();
    }

    /**
     * Stores a block-to-ID mapping.
     *
     * <p>This avoids {@code computeIfAbsent} because the cache may be cleared when
     * over capacity. Mutating the same map from inside a compute mapping function
     * is not worth the footgun.</p>
     *
     * @param block block key
     * @param id    resolved ID
     */
    private void cacheBlockId(final Block block, final String id) {
        evictIfNeeded(blockToIdCache);
        blockToIdCache.putIfAbsent(block, id);
    }

    /**
     * Stores an ID-to-block mapping.
     *
     * @param id    registry ID string
     * @param block resolved block
     */
    private void cacheBlock(final String id, final Block block) {
        evictIfNeeded(idToBlockCache);
        idToBlockCache.putIfAbsent(id, block);
    }
}