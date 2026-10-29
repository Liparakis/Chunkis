package io.liparakis.chunkis.world.restoration.nbt;

import io.liparakis.chunkis.world.tracking.save.RecentChunkBlocks;
import com.google.common.collect.MapMaker;
import io.liparakis.chunkis.core.ChunkDelta;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.world.chunk.PaletteProvider;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;

/**
 * Moves a finished chunk's block baseline off the server thread: the load builds each section's block container
 * from the delta on a worker thread and vanilla's chunk reader takes it as is (see
 * {@code ChunkSerializerMixin}), instead of Chunkis encoding the states to NBT for vanilla to parse straight back,
 * and the restore does not have to write every block on the server thread.
 *
 * <p>A built container waits on its section's NBT compound, held by identity and weakly, so a load that never
 * reaches vanilla's reader leaves nothing behind. The restore skips its block writes only when vanilla's reader
 * took every built container of the load; otherwise it writes the blocks as before, so a missed hand-over can
 * never load a chunk as air.</p>
 *
 * <p>The restore may skip its block writes only for the exact delta the sections were built from, unchanged
 * since: the delta used at conversion can differ from the one the load NBT was built from (a newer tracked
 * state), and that one must still be written. {@link #consume} checks both the instance and its mutation
 * generation. When anything does not fit (an unresolved block state, a block outside the world, a section
 * without a stored entry), nothing is embedded and the restore writes the blocks as before.</p>
 */
public final class PreloadedBlockSections {

    /**
     * Mutation generation of each delta whose blocks were embedded, keyed by instance. Weak, so loads that
     * never reach the restore leave nothing behind.
     */
    private static final Map<ChunkDelta<?, ?>, Embedding> EMBEDDED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Content fingerprints of consumed embeddings, for the restore to take right after {@link #consume}.
     */
    private static final Map<ChunkDelta<?, ?>, long[]> CONSUMED_PRINTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Built containers waiting for vanilla's chunk reader, by their section's NBT compound (identity, weak keys).
     */
    private static final ConcurrentMap<NbtCompound, Prebuilt> PREBUILT = new MapMaker().weakKeys().makeMap();

    /**
     * Container taken for the section vanilla's reader is building on this thread.
     */
    private static final ThreadLocal<PalettedContainer<BlockState>> TAKEN = new ThreadLocal<>();

    /**
     * One load's embedding: the delta generation it was built from and how many containers vanilla has yet to
     * take.
     *
     * @param generation mutation generation of the delta when built
     * @param pending    built containers not yet taken by vanilla's reader
     */
    private record Embedding(long generation, AtomicInteger pending, long[] contentPrint) {
    }

    /**
     * A built container and the load it belongs to.
     *
     * @param container block container for one section
     * @param embedding owning load
     */
    private record Prebuilt(PalettedContainer<BlockState> container, Embedding embedding) {
    }

    /**
     * Static holder; not instantiable.
     */
    private PreloadedBlockSections() {
    }

    /**
     * Adds the delta's blocks to the sections of a finished-chunk load root, when it is one.
     *
     * @param world world the chunk belongs to
     * @param root  load root built by {@link CisNbtUtil#buildLoadChunkNbt}
     * @param delta delta the root was built from; not modified
     */
    public static void embed(final ServerWorld world, final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        if (!CisNbtUtil.loadsAsFinishedChunk(delta.getChunkMetadata())) {
            return;
        }
        final long generation = delta.getMutationGeneration();
        if (delta.takeLoadedBlocks() instanceof RecentChunkBlocks.LoadedSections loaded
                && embedLoadedSections(world, root, delta, generation, loaded)) {
            return;
        }
        final SectionIndices[] built = buildSectionIndices(world, delta);
        if (built == null) {
            return;
        }
        final NbtList sections = root.getListOrEmpty(CisNbtUtil.SECTIONS_KEY);
        final NbtCompound[] entries = new NbtCompound[built.length];
        for (int i = 0; i < sections.size(); i++) {
            final NbtCompound entry = sections.getCompoundOrEmpty(i);
            final int index = entry.getByte(StoredSections.Y_KEY, (byte) 0) - world.getBottomSectionCoord();
            if (index >= 0 && index < entries.length) {
                entries[index] = entry;
            }
        }
        for (int i = 0; i < built.length; i++) {
            if (built[i] != null && entries[i] == null) {
                return;
            }
        }
        final PaletteProvider<BlockState> provider = world.getPalettesFactory().blockStatesStrategy();
        final Embedding embedding = new Embedding(generation, new AtomicInteger(), contentPrint(built));
        for (int i = 0; i < built.length; i++) {
            if (built[i] != null) {
                embedding.pending().incrementAndGet();
                PREBUILT.put(entries[i], new Prebuilt(built[i].build(provider), embedding));
            }
        }
        EMBEDDED.put(delta, embedding);
    }

    /**
     * Takes the container built for a section about to be read, for {@link #takeBuilt} to hand to vanilla.
     *
     * @param section section NBT compound vanilla's reader is at
     * @return {@code true} when a container was built for it; vanilla must then skip its {@code block_states}
     */
    public static boolean take(final NbtCompound section) {
        final Prebuilt prebuilt = PREBUILT.remove(section);
        if (prebuilt == null) {
            // Every section read passes here first, so a container left by a read that failed midway is dropped.
            TAKEN.remove();
            return false;
        }
        TAKEN.set(prebuilt.container());
        prebuilt.embedding().pending().decrementAndGet();
        return true;
    }

    /**
     * Hands over the container {@link #take} took on this thread, once.
     *
     * @return the container, or {@code null} when none was taken
     */
    public static PalettedContainer<BlockState> takeBuilt() {
        final PalettedContainer<BlockState> container = TAKEN.get();
        TAKEN.remove();
        return container;
    }

    /**
     * Embeds blocks the delta was filled with straight from their containers: vanilla gets copies of them, and the
     * content fingerprint comes along, so nothing is rebuilt from the instructions.
     *
     * @param world      world the chunk belongs to
     * @param root       load root
     * @param delta      the delta, holding exactly these blocks
     * @param generation its mutation generation
     * @param loaded     the containers and their fingerprint
     * @return {@code false} when they do not fit the root, and the caller must build the sections instead
     */
    private static boolean embedLoadedSections(final ServerWorld world, final NbtCompound root,
            final ChunkDelta<BlockState, NbtCompound> delta, final long generation,
            final RecentChunkBlocks.LoadedSections loaded) {
        final PalettedContainer<BlockState>[] containers = loaded.sections();
        final NbtCompound[] entries = sectionEntries(world, root, world.countVerticalSections());
        if (containers.length != entries.length) {
            return false;
        }
        for (int i = 0; i < containers.length; i++) {
            if (containers[i] != null && entries[i] == null) {
                return false;
            }
        }
        final Embedding embedding = new Embedding(generation, new AtomicInteger(), loaded.content());
        for (int i = 0; i < containers.length; i++) {
            if (containers[i] != null) {
                embedding.pending().incrementAndGet();
                PREBUILT.put(entries[i], new Prebuilt(containers[i].copy(), embedding));
            }
        }
        EMBEDDED.put(delta, embedding);
        return true;
    }

    /**
     * Indexes a load root's section entries by section index.
     *
     * @param world world the chunk belongs to
     * @param root  load root
     * @param count number of sections
     * @return entries by section index, {@code null} where the root has none
     */
    private static NbtCompound[] sectionEntries(final ServerWorld world, final NbtCompound root, final int count) {
        final NbtList sections = root.getListOrEmpty(CisNbtUtil.SECTIONS_KEY);
        final NbtCompound[] entries = new NbtCompound[count];
        for (int i = 0; i < sections.size(); i++) {
            final NbtCompound entry = sections.getCompoundOrEmpty(i);
            final int index = entry.getByte(StoredSections.Y_KEY, (byte) 0) - world.getBottomSectionCoord();
            if (index >= 0 && index < entries.length) {
                entries[index] = entry;
            }
        }
        return entries;
    }

    /**
     * Fingerprints the built sections as a stored entry keeps them
     * ({@link io.liparakis.chunkis.world.tracking.save.DeferredBlocksSnapshotView.ContentPrint}).
     *
     * @param built section indices by section index, {@code null} where none was built
     * @return two 64-bit hashes
     */
    private static long[] contentPrint(final SectionIndices[] built) {
        final var print = new io.liparakis.chunkis.world.tracking.save.DeferredBlocksSnapshotView.ContentPrint();
        final int[] ids = new int[4096];
        for (int section = 0; section < built.length; section++) {
            if (built[section] != null && built[section].fillPrintIds(ids)) {
                print.section(section, ids);
            }
        }
        return print.finish();
    }

    /**
     * Takes, once, the fingerprint of the blocks a consumed embedding loaded; see {@link #consume}.
     *
     * @param delta delta just consumed
     * @return the fingerprint, or {@code null}
     */
    public static long[] takeContentPrint(final ChunkDelta<?, ?> delta) {
        return CONSUMED_PRINTS.remove(delta);
    }

    /**
     * Reports, once, whether the delta's blocks were embedded in the load NBT and the delta is unchanged since.
     *
     * @param delta delta about to be restored
     * @return true when the chunk's sections already hold exactly the delta's blocks
     */
    public static boolean consume(final ChunkDelta<?, ?> delta) {
        final Embedding embedding = EMBEDDED.remove(delta);
        final boolean preloaded = embedding != null && embedding.generation() == delta.getMutationGeneration()
                && embedding.pending().get() == 0;
        if (preloaded) {
            CONSUMED_PRINTS.put(delta, embedding.contentPrint());
        }
        return preloaded;
    }

    /**
     * Collects every block of the delta per section as palette indices, in instruction order so a position
     * written twice ends with its last state, exactly as the restore's own writes would.
     *
     * @param world world the chunk belongs to
     * @param delta source delta
     * @return indices by section index, {@code null} for sections holding only air, or {@code null} when a
     *         block cannot be placed
     */
    private static SectionIndices[] buildSectionIndices(final ServerWorld world,
            final ChunkDelta<BlockState, NbtCompound> delta) {
        final PaletteProvider<BlockState> provider = world.getPalettesFactory().blockStatesStrategy();
        final int bottomY = world.getBottomY();
        final SectionIndices[] sections = new SectionIndices[world.countVerticalSections()];
        final int[] placed = {0};
        final boolean[] outside = {false};
        delta.forEachBlockInstruction((x, y, z, paletteId, state) -> {
            final int index = (y - bottomY) >> 4;
            if (y < bottomY || index >= sections.length) {
                outside[0] = true;
                return;
            }
            if (sections[index] == null) {
                sections[index] = new SectionIndices(provider.getSize());
            }
            sections[index].set(provider.computeIndex(x, y & 15, z), paletteId, state);
            placed[0]++;
        });
        // Instructions whose state did not resolve are skipped; the restore would skip them too, but report them.
        return outside[0] || placed[0] != delta.getBlockChangesCount() ? null : sections;
    }

    /**
     * One section's blocks as indices into a section palette, the shape vanilla serializes a container in.
     */
    private static final class SectionIndices {

        /**
         * Marks a position the delta does not write, which is air.
         */
        private static final int UNSET = -1;

        /**
         * Palette index per position.
         */
        private final int[] indices;

        /**
         * Section palette, in first-use order.
         */
        private final List<BlockState> palette = new ArrayList<>();

        /**
         * Palette index of each state in {@link #palette}.
         */
        private final Reference2IntOpenHashMap<BlockState> paletteIndex = new Reference2IntOpenHashMap<>();

        /**
         * Section palette index by the delta's palette id, so a chunk's many blocks of a few states skip the
         * state lookup; {@link #UNSET} until the id is first seen.
         */
        private int[] indexByDeltaId = new int[0];

        /**
         * Positions set so far; a dense section sets them all and needs no air fill.
         */
        private int filled;

        /**
         * Creates an all-unset section.
         *
         * @param size positions per section
         */
        SectionIndices(final int size) {
            indices = new int[size];
            Arrays.fill(indices, UNSET);
            paletteIndex.defaultReturnValue(UNSET);
        }

        /**
         * Sets one position.
         *
         * @param position  storage index
         * @param deltaId   the delta's palette id of {@code state}
         * @param state     block state
         */
        void set(final int position, final int deltaId, final BlockState state) {
            if (deltaId >= indexByDeltaId.length) {
                final int previous = indexByDeltaId.length;
                indexByDeltaId = Arrays.copyOf(indexByDeltaId, Math.max(deltaId + 1, previous * 2));
                Arrays.fill(indexByDeltaId, previous, indexByDeltaId.length, UNSET);
            }
            int index = indexByDeltaId[deltaId];
            if (index == UNSET) {
                index = indexOf(state);
                indexByDeltaId[deltaId] = index;
            }
            if (indices[position] == UNSET) {
                filled++;
            }
            indices[position] = index;
        }

        /**
         * Returns the palette index of a state, adding it on first use.
         *
         * @param state block state
         * @return palette index
         */
        private int indexOf(final BlockState state) {
            int index = paletteIndex.getInt(state);
            if (index == UNSET) {
                index = palette.size();
                palette.add(state);
                paletteIndex.put(state, index);
            }
            return index;
        }

            /**
         * Fills the section's print ids ({@code ContentPrint#idOf}) by position.
         *
         * @param ids 4096 slots to fill
         * @return {@code true} when the section holds a non-air block
         */
        boolean fillPrintIds(final int[] ids) {
            final int[] paletteIds = new int[palette.size()];
            for (int i = 0; i < paletteIds.length; i++) {
                paletteIds[i] = io.liparakis.chunkis.world.tracking.save.DeferredBlocksSnapshotView.ContentPrint
                        .idOf(palette.get(i));
            }
            boolean any = false;
            for (int index = 0; index < indices.length; index++) {
                // Positions no block was set at load as air.
                ids[index] = indices[index] == UNSET ? -1 : paletteIds[indices[index]];
                any |= ids[index] >= 0;
            }
            return any;
        }

        /**
         * Builds the section's block container the way vanilla's reader builds one from its saved form.
         *
         * @param provider block-state palette provider
         * @return the container
         */
        PalettedContainer<BlockState> build(final PaletteProvider<BlockState> provider) {
            int air = UNSET;
            for (int i = 0; filled < indices.length && i < indices.length; i++) {
                if (indices[i] == UNSET) {
                    if (air == UNSET) {
                        air = indexOf(Blocks.AIR.getDefaultState());
                    }
                    indices[i] = air;
                }
            }
            final int bits = provider.createTypeFromSize(palette.size()).bitsInStorage();
            final Optional<LongStream> data = bits == 0 ? Optional.empty()
                    : Optional.of(LongStream.of(new PackedIntegerArray(bits, indices.length, indices).getData()));
            return PalettedContainer.read(provider, new ReadableContainer.Serialized<>(List.copyOf(palette), data))
                    .getOrThrow(message -> new IllegalStateException("Chunkis: Failed building a block section: "
                            + message));
        }
    }
}
