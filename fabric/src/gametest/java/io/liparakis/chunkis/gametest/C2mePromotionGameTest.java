package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.integration.c2me.C2meChunkPromotion;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.jspecify.annotations.NonNull;

/**
 * Checks the actual optional C2ME hook, including worker-thread scope cleanup.
 */
@SuppressWarnings("unused")
public final class C2mePromotionGameTest {

    /**
     * The constructor must see its unpublished target only inside C2ME's original conversion.
     *
     * @param context game test context
     * @throws ReflectiveOperationException if the installed C2ME conversion method changed
     */
    @GameTest(maxTicks = 200)
    public void c2meConstructionMarksOnlyItsUnpublishedChunk(final GameTestHelper context)
            throws ReflectiveOperationException {
        if (!FabricLoader.getInstance()
                .isModLoaded("c2me")) {
            context.succeed();
            return;
        }
        final ServerLevel world = context.getLevel();
        final ChunkPos pos = ChunkPos.containing(context.absolutePos(BlockPos.ZERO));
        final AtomicBoolean observed = new AtomicBoolean();
        final ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, world, world.palettedContainerFactory(), null) {
            @Override
            public @NonNull ChunkPos getPos() {
                if (C2meChunkPromotion.isConstructing(world, chunkPos.x(), chunkPos.z())) {
                    observed.set(true);
                }
                return super.getPos();
            }
        };
        final Class<?> owner = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible");
        final Method conversion = owner.getDeclaredMethod("toFullChunk", ProtoChunk.class, ServerLevel.class);
        conversion.setAccessible(true);
        final CompletableFuture<LevelChunk> worker = CompletableFuture.supplyAsync(() -> {
            try {
                final LevelChunk result = (LevelChunk) conversion.invoke(null, proto, world);
                if (C2meChunkPromotion.isConstructing(world, pos.x(), pos.z())) {
                    throw new IllegalStateException("C2ME construction scope leaked onto its worker");
                }
                return result;
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("C2ME full-chunk construction failed at " + pos, e);
            }
        });
        context.startSequence()
                .thenWaitUntil(() -> {
                    context.assertTrue(worker.isDone(), Component.literal("C2ME constructor worker has not returned"));
                    context.assertTrue(worker.join()
                            .getPos()
                            .equals(pos), Component.literal("C2ME conversion changed the chunk position"));
                    context.assertTrue(observed.get(),
                            Component.literal("C2ME promotion hook did not wrap construction"));
                    context.assertTrue(!C2meChunkPromotion.isConstructing(world, pos.x(), pos.z()),
                            Component.literal("Worker construction scope leaked onto the server"));
                })
                .thenSucceed();
    }
}
