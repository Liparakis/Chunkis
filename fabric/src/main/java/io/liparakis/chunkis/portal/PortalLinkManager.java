package io.liparakis.chunkis.portal;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.world.tracking.save.ChunkisStoragePaths;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

/**
 * Persistent bidirectional portal pairing table.
 *
 * <p>
 * The portal chunk index only tells Chunkis which chunks contain portal
 * blocks. It does not preserve portal identity. This table persists explicit
 * "portal A links to portal B" relationships so return travel can prefer the
 * original partner instead of relying on a fresh nearest-portal search.
 *
 * @author Liparakis
 * @version 1.2
 *
 */
public final class PortalLinkManager {

    /**
     * Key for the list of portal pairing entries in NBT.
     */
    private static final String ENTRIES_KEY = "entries";
    /**
     * Key for the source portal anchor in an NBT entry.
     */
    private static final String SOURCE_KEY = "source";
    /**
     * Key for the destination portal anchor in an NBT entry.
     */
    private static final String DESTINATION_KEY = "destination";
    /**
     * Key for the dimension identifier in a portal anchor NBT.
     */
    private static final String NBT_DIMENSION = "dimension";
    /**
     * Key for the X coordinate in a portal anchor NBT.
     */
    private static final String NBT_X = "x";
    /**
     * Key for the Y coordinate in a portal anchor NBT.
     */
    private static final String NBT_Y = "y";
    /**
     * Key for the Z coordinate in a portal anchor NBT.
     */
    private static final String NBT_Z = "z";
    /**
     * Key for the orientation axis in a portal anchor NBT.
     */
    private static final String NBT_AXIS = "axis";

    /**
     * Minimum portal width in blocks (used for anchor validation).
     */
    private static final int MIN_PORTAL_WIDTH = 2;
    /**
     * Minimum portal height in blocks (used for anchor validation).
     */
    private static final int MIN_PORTAL_HEIGHT = 3;

    /**
     * Active portal link tables indexed by their filesystem path.
     */
    private static final Map<Path, PortalLinkTable> TABLES = new ConcurrentHashMap<>();

    private PortalLinkManager() {
        throw new AssertionError("Utility class");
    }

    /**
     * Records a bidirectional link between a source portal and a destination portal
     * whose anchor (lower-corner and axis) is already known.
     *
     * <p>
     * Use this overload when the destination portal was just created and its
     * geometry is provided directly rather than resolved from world state.
     *
     * @param sourceWorld            world containing the source portal
     * @param sourcePortalPos        any block position within the source portal
     * @param destinationWorld       world containing the destination portal
     * @param destinationLowerCorner bottom-left corner of the destination portal
     *                               frame
     * @param destinationAxis        orientation axis of the destination portal
     */
    public static void registerBidirectional(
            final ServerWorld sourceWorld,
            final BlockPos sourcePortalPos,
            final ServerWorld destinationWorld,
            final BlockPos destinationLowerCorner,
            final Direction.Axis destinationAxis) {
        final PortalAnchor sourceAnchor = resolvePortalAnchor(sourceWorld, sourcePortalPos).orElse(null);
        if (sourceAnchor == null) {
            return;
        }

        final PortalAnchor destinationAnchor = new PortalAnchor(
                destinationWorld.getRegistryKey(),
                destinationLowerCorner.toImmutable(),
                destinationAxis);

        getTable(sourceWorld.getServer()).putPair(sourceAnchor, destinationAnchor);
    }

    /**
     * Records a bidirectional link between two existing portals, resolving both
     * anchors from world state.
     *
     * <p>
     * If either position does not contain a valid nether portal block, the
     * registration is silently skipped.
     *
     * @param sourceWorld          world containing the source portal
     * @param sourcePortalPos      any block position within the source portal
     * @param destinationWorld     world containing the destination portal
     * @param destinationPortalPos any block position within the destination portal
     */
    public static void registerBidirectionalIfPresent(
            final ServerWorld sourceWorld,
            final BlockPos sourcePortalPos,
            final ServerWorld destinationWorld,
            final BlockPos destinationPortalPos) {
        final PortalAnchor sourceAnchor = resolvePortalAnchor(sourceWorld, sourcePortalPos).orElse(null);
        final PortalAnchor destinationAnchor = resolvePortalAnchor(destinationWorld, destinationPortalPos).orElse(null);

        if (sourceAnchor == null || destinationAnchor == null) {
            return;
        }

        getTable(sourceWorld.getServer()).putPair(sourceAnchor, destinationAnchor);
    }

    /**
     * Returns a teleport target for the linked destination portal, if a valid link
     * exists from the source portal.
     *
     * <p>
     * If the recorded destination portal is no longer present in the world, the
     * stale link is removed and an empty Optional is returned, allowing the caller
     * to fall through to vanilla logic.
     *
     * @param destinationWorld the expected destination world
     * @param entity           the entity being teleported
     * @param sourceWorld      the world the entity is leaving
     * @param sourcePortalPos  any block position within the source portal
     * @return a teleport target if a valid link exists, otherwise empty
     */
    public static Optional<TeleportTarget> getLinkedTarget(
            final ServerWorld destinationWorld,
            final Entity entity,
            final ServerWorld sourceWorld,
            final BlockPos sourcePortalPos) {
        final PortalAnchor sourceAnchor = resolvePortalAnchor(sourceWorld, sourcePortalPos).orElse(null);
        if (sourceAnchor == null) {
            return Optional.empty();
        }

        final PortalLinkTable table = getTable(destinationWorld.getServer());
        PortalAnchor matchedSourceAnchor = sourceAnchor;
        PortalAnchor destinationAnchor = table.get(sourceAnchor);

        if (destinationAnchor == null) {
            final PortalAnchor legacySourceAnchor =
                    resolveLegacyPortalAnchor(sourceWorld, sourcePortalPos).orElse(null);
            if (legacySourceAnchor != null && !legacySourceAnchor.equals(sourceAnchor)) {
                destinationAnchor = table.get(legacySourceAnchor);
                matchedSourceAnchor = legacySourceAnchor;
            }
        }

        if (destinationAnchor == null
                || destinationAnchor.worldKey() != destinationWorld.getRegistryKey()) {
            return Optional.empty();
        }

        final PortalAnchor normalizedDestination =
                normalizePortalAnchor(destinationWorld, destinationAnchor).orElse(null);

        if (normalizedDestination == null) {
            table.removePair(matchedSourceAnchor, destinationAnchor);
            return Optional.empty();
        }

        if (!matchedSourceAnchor.equals(sourceAnchor)
                || !normalizedDestination.equals(destinationAnchor)) {
            table.removePair(matchedSourceAnchor, destinationAnchor);
            table.putPair(sourceAnchor, normalizedDestination);
        }

        return Optional.of(PortalArrivalFallback.createLinkedTeleportTarget(
                destinationWorld,
                entity,
                normalizedDestination.lowerCorner(),
                normalizedDestination.axis()));
    }

    /**
     * Flushes and discards the portal link table for the given server instance.
     *
     * <p>
     * Should be called on server shutdown or world unload.
     *
     * @param server the server whose table should be closed; ignored if
     *               {@code null}
     */
    public static void close(final MinecraftServer server) {
        if (server == null) {
            return;
        }

        final Path path = resolvePath(server);
        final PortalLinkTable table = TABLES.remove(path);
        if (table != null) {
            table.close();
        }
    }

    /**
     * Flushes and discards all portal link tables.
     *
     * <p>
     * Intended for use in test teardown or full server shutdown.
     */
    public static void clear() {
        TABLES.values().forEach(PortalLinkTable::close);
        TABLES.clear();
    }

    /**
     * Returns the portal link table for the given server, creating it if it
     * does not already exist.
     *
     * @param server the server instance
     * @return the active portal link table
     */
    private static PortalLinkTable getTable(final MinecraftServer server) {
        return TABLES.computeIfAbsent(
                resolvePath(Objects.requireNonNull(server, "server")),
                PortalLinkTable::new);
    }

    /**
     * Resolves the filesystem path where the portal link table should be persisted.
     *
     * @param server the server instance
     * @return the path to the portal links NBT file
     */
    private static Path resolvePath(final MinecraftServer server) {
        return ChunkisStoragePaths.computePortalLinksFile(server.getSavePath(WorldSavePath.ROOT));
    }

    /**
     * Resolves a {@link PortalAnchor} from any block position within a nether
     * portal.
     *
     * <p>
     * Walks down to the lowest portal row, then sideways to the leftmost column,
     * to arrive at the canonical lower-left corner of the portal frame. Returns
     * empty
     * if {@code portalPos} does not contain a nether portal block.
     *
     * @param world     the world to query
     * @param portalPos any block position within the portal
     * @return the canonical anchor, or empty if no portal is present
     */
    private static Optional<PortalAnchor> resolvePortalAnchor(
            final ServerWorld world,
            final BlockPos portalPos) {
        return resolvePortalAnchor(world, portalPos, false);
    }

    /**
     * Resolves a portal anchor using the pre-fix edge convention.
     *
     * <p>Older saved link tables may contain anchors from the opposite portal
     * edge. This lets Chunkis find and rewrite those entries on first use.</p>
     *
     * @param world     the world to query
     * @param portalPos any block position within the portal
     * @return the legacy anchor, or empty if no portal is present
     */
    private static Optional<PortalAnchor> resolveLegacyPortalAnchor(
            final ServerWorld world,
            final BlockPos portalPos) {
        return resolvePortalAnchor(world, portalPos, true);
    }

    /**
     * Resolves a portal anchor from any portal block.
     *
     * @param world      the world to query
     * @param portalPos  any block position within the portal
     * @param legacyEdge {@code true} to use the old edge convention
     * @return the resolved anchor, or empty if no portal is present
     */
    private static Optional<PortalAnchor> resolvePortalAnchor(
            final ServerWorld world,
            final BlockPos portalPos,
            final boolean legacyEdge) {
        final BlockState portalState = world.getBlockState(portalPos);
        if (!portalState.isOf(Blocks.NETHER_PORTAL)) {
            return Optional.empty();
        }

        final Direction.Axis axis = portalState.getOrEmpty(NetherPortalBlock.AXIS).orElse(Direction.Axis.X);
        final Direction widthDirection = widthDirection(axis);
        final Direction edgeDirection = legacyEdge ? widthDirection : widthDirection.getOpposite();
        final BlockPos.Mutable cursor = portalPos.mutableCopy();

        // Walk to the bottom of the portal column.
        while (true) {
            final BlockPos below = cursor.down();
            if (isDifferentPortalBlock(world, below, axis)) {
                break;
            }
            cursor.move(Direction.DOWN);
        }

        // Walk to the canonical edge of the portal row.
        while (true) {
            final BlockPos beside = cursor.offset(edgeDirection);
            if (isDifferentPortalBlock(world, beside, axis)) {
                break;
            }
            cursor.move(edgeDirection);
        }

        return Optional.of(new PortalAnchor(world.getRegistryKey(), cursor.toImmutable(), axis));
    }

    /**
     * Returns {@code true} if {@code pos} contains a nether portal block with the
     * given orientation axis.
     *
     * @param world the world to query
     * @param pos   the position to check
     * @param axis  the expected portal axis
     * @return {@code true} if the block is a same-axis nether portal
     */
    private static boolean isDifferentPortalBlock(
            final ServerWorld world,
            final BlockPos pos,
            final Direction.Axis axis) {
        final BlockState state = world.getBlockState(pos);
        return !state.isOf(Blocks.NETHER_PORTAL)
                || state.getOrEmpty(NetherPortalBlock.AXIS).orElse(axis) != axis;
    }

    /**
     * Returns {@code true} if the portal described by {@code anchor} still exists
     * in
     * the world.
     *
     * <p>
     * Forces the containing chunk to load before checking, then verifies a
     * {@value MIN_PORTAL_WIDTH}×{@value MIN_PORTAL_HEIGHT} block region starting
     * from
     * the lower-left corner of the portal frame.
     *
     * @param world  the world to check
     * @param anchor the anchor describing the portal's expected position and axis
     * @return {@code true} if the minimum portal structure is intact
     */
    private static boolean isValidPortalAnchor(
            final ServerWorld world,
            final PortalAnchor anchor) {
        // Force chunk load so block queries don't return empty air for unloaded chunks.
        world.getChunk(anchor.lowerCorner().getX() >> 4, anchor.lowerCorner().getZ() >> 4);

        final Direction widthDirection = widthDirection(anchor.axis());

        for (int width = 0; width < MIN_PORTAL_WIDTH; width++) {
            for (int height = 0; height < MIN_PORTAL_HEIGHT; height++) {
                final BlockPos pos = anchor.lowerCorner().offset(widthDirection, width).up(height);
                if (isDifferentPortalBlock(world, pos, anchor.axis())) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Converts a possibly legacy anchor to the current canonical anchor and
     * verifies that the portal still exists.
     *
     * @param world  destination world
     * @param anchor anchor from the persisted table
     * @return normalized anchor, or empty if the portal is gone
     */
    private static Optional<PortalAnchor> normalizePortalAnchor(
            final ServerWorld world,
            final PortalAnchor anchor) {
        world.getChunk(anchor.lowerCorner().getX() >> 4, anchor.lowerCorner().getZ() >> 4);

        final PortalAnchor normalized = resolvePortalAnchor(world, anchor.lowerCorner()).orElse(null);
        if (normalized == null || !isValidPortalAnchor(world, normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * Returns the horizontal direction used by portal builders for the interior
     * width from the canonical lower corner.
     *
     * @param axis portal axis
     * @return interior width direction
     */
    private static Direction widthDirection(final Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
    }

    /**
     * Encapsulates the canonical identity of a nether portal.
     *
     * @param worldKey    the world containing the portal
     * @param lowerCorner the bottom-left corner of the portal frame
     * @param axis        the orientation axis of the portal
     */
    private record PortalAnchor(
            RegistryKey<World> worldKey,
            BlockPos lowerCorner,
            Direction.Axis axis) {

    }

    /**
     * Manages a single persistent table of portal links.
     */
    private static final class PortalLinkTable {

        /**
         * Filesystem path where this table is stored.
         */
        private final Path path;
        /**
         * In-memory bidirectional pairing map.
         */
        private final ConcurrentHashMap<PortalAnchor, PortalAnchor> links;
        /**
         * Whether the table has been modified since it was last saved.
         */
        private boolean dirty;

        /**
         * Initializes a new link table, loading existing data from the given path.
         *
         * @param path the persistence path
         */
        private PortalLinkTable(final Path path) {
            this.path = Objects.requireNonNull(path, "path");
            this.links = load(path);
            this.dirty = false;
        }

        /**
         * Loads a portal link table from an NBT file.
         *
         * @param path path to the NBT file
         * @return a map populated with links from the file
         */
        private static ConcurrentHashMap<PortalAnchor, PortalAnchor> load(final Path path) {
            final ConcurrentHashMap<PortalAnchor, PortalAnchor> loaded = new ConcurrentHashMap<>();

            if (!Files.isRegularFile(path)) {
                return loaded;
            }

            try (final DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                final NbtCompound root = NbtIo.readCompound(input);
                final NbtList entries = root.getList(ENTRIES_KEY).orElseGet(NbtList::new);

                for (final net.minecraft.nbt.NbtElement element : entries) {
                    if (!(element instanceof NbtCompound entry)) {
                        continue;
                    }

                    final Optional<PortalAnchor> source = readAnchor(entry.getCompound(SOURCE_KEY).orElse(null));
                    final Optional<PortalAnchor> destination = readAnchor(
                            entry.getCompound(DESTINATION_KEY).orElse(null));

                    if (source.isPresent() && destination.isPresent()) {
                        loaded.put(source.get(), destination.get());
                    }
                }
            } catch (final Exception e) {
                Chunkis.LOGGER.warn("Chunkis: Failed to load portal links {}", path, e);
            }

            return loaded;
        }

        /**
         * Serializes a {@link PortalAnchor} to NBT.
         *
         * @param anchor the anchor to serialize
         * @return the resulting NBT compound
         */
        private static NbtCompound writeAnchor(final PortalAnchor anchor) {
            final NbtCompound nbt = new NbtCompound();
            nbt.putString(NBT_DIMENSION, anchor.worldKey().getValue().toString());
            nbt.putInt(NBT_X, anchor.lowerCorner().getX());
            nbt.putInt(NBT_Y, anchor.lowerCorner().getY());
            nbt.putInt(NBT_Z, anchor.lowerCorner().getZ());
            nbt.putString(NBT_AXIS, anchor.axis().asString());
            return nbt;
        }

        /**
         * Deserializes a {@link PortalAnchor} from NBT.
         *
         * <p>
         * Returns empty if {@code nbt} is {@code null}, if the dimension identifier
         * cannot be parsed, or if the axis value is not {@code "x"} or {@code "z"}.
         *
         * @param nbt the compound to read from, or {@code null}
         * @return the deserialized anchor, or empty
         */
        private static Optional<PortalAnchor> readAnchor(final NbtCompound nbt) {
            if (nbt == null) {
                return Optional.empty();
            }

            final Optional<String> dimensionRaw = nbt.getString(NBT_DIMENSION);
            final Optional<String> axisRaw = nbt.getString(NBT_AXIS);

            if (dimensionRaw.isEmpty() || axisRaw.isEmpty()) {
                return Optional.empty();
            }

            final Identifier dimensionId = Identifier.tryParse(dimensionRaw.get());
            if (dimensionId == null) {
                return Optional.empty();
            }

            final Direction.Axis axis = switch (axisRaw.get()) {
                case "x" -> Direction.Axis.X;
                case "z" -> Direction.Axis.Z;
                default -> null;
            };

            if (axis == null) {
                return Optional.empty();
            }

            return Optional.of(new PortalAnchor(
                    RegistryKey.of(RegistryKeys.WORLD, dimensionId),
                    new BlockPos(
                            nbt.getInt(NBT_X, 0),
                            nbt.getInt(NBT_Y, 0),
                            nbt.getInt(NBT_Z, 0)),
                    axis));
        }

        /**
         * Returns the destination portal linked from the given source portal.
         *
         * @param source the source portal anchor
         * @return the destination anchor, or {@code null} if no link exists
         */
        PortalAnchor get(final PortalAnchor source) {
            return links.get(source);
        }

        /**
         * Inserts a bidirectional link (forward + reverse) and flushes to disk once.
         *
         * <p>
         * Batching both directions into a single save call avoids the double disk
         * write that would result from two separate {@code put} calls.
         */
        void putPair(final PortalAnchor a, final PortalAnchor b) {
            final boolean changedForward = !b.equals(links.put(a, b));
            final boolean changedReverse = !a.equals(links.put(b, a));

            if (changedForward || changedReverse) {
                dirty = true;
                save();
            }
        }

        /**
         * Removes a bidirectional link (forward + reverse) and flushes to disk once.
         */
        void removePair(final PortalAnchor a, final PortalAnchor b) {
            final boolean removedForward = links.remove(a) != null;
            final boolean removedReverse = links.remove(b) != null;

            if (removedForward || removedReverse) {
                dirty = true;
                save();
            }
        }

        /**
         * Flushes the table to disk if it has been modified.
         */
        void close() {
            if (dirty) {
                save();
            }
        }

        /**
         * Serializes the entire link table to NBT and writes it to disk.
         */
        private void save() {
            try {
                Files.createDirectories(path.getParent());

                final NbtCompound root = new NbtCompound();
                final NbtList entries = new NbtList();

                links.forEach((source, destination) -> {
                    final NbtCompound entry = new NbtCompound();
                    entry.put(SOURCE_KEY, writeAnchor(source));
                    entry.put(DESTINATION_KEY, writeAnchor(destination));
                    entries.add(entry);
                });

                root.put(ENTRIES_KEY, entries);

                try (final DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
                    NbtIo.writeCompound(root, output);
                }

                dirty = false;
            } catch (final IOException e) {
                Chunkis.LOGGER.error("Chunkis: Failed to save portal links {}", path, e);
            }
        }
    }
}


