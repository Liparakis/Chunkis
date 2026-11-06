package io.liparakis.chunkis.gametest;

import io.liparakis.chunkis.integration.c2me.C2meChunkPromotion;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;

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
    public void c2meConstructionMarksOnlyItsUnpublishedChunk(final TestContext context)
            throws ReflectiveOperationException {
        if (!FabricLoader.getInstance().isModLoaded("c2me")) {
            context.complete();
            return;
        }
        final ServerWorld world = context.getWorld();
        final ChunkPos pos = new ChunkPos(context.getAbsolutePos(BlockPos.ORIGIN));
        final AtomicBoolean observed = new AtomicBoolean();
        final ProtoChunk proto = new ProtoChunk(pos, UpgradeData.NO_UPGRADE_DATA, world,
                world.getPalettesFactory(), null) {
            @Override
            public ChunkPos getPos() {
                if (C2meChunkPromotion.isConstructing(world, pos.x, pos.z)) {
                    observed.set(true);
                }
                return super.getPos();
            }
        };
        final Class<?> owner = Class.forName(
                "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible");
        final Method conversion = owner.getDeclaredMethod("toFullChunk", ProtoChunk.class, ServerWorld.class);
        conversion.setAccessible(true);
        final CompletableFuture<WorldChunk> worker = CompletableFuture.supplyAsync(() -> {
            try {
                final WorldChunk result = (WorldChunk) conversion.invoke(null, proto, world);
                if (C2meChunkPromotion.isConstructing(world, pos.x, pos.z)) {
                    throw new IllegalStateException("C2ME construction scope leaked onto its worker");
                }
                return result;
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("C2ME full-chunk construction failed at " + pos, e);
            }
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    context.assertTrue(worker.isDone(), Text.literal("C2ME constructor worker has not returned"));
                    context.assertTrue(worker.join().getPos().equals(pos),
                            Text.literal("C2ME conversion changed the chunk position"));
                    context.assertTrue(observed.get(), Text.literal("C2ME promotion hook did not wrap construction"));
                    context.assertTrue(!C2meChunkPromotion.isConstructing(world, pos.x, pos.z),
                            Text.literal("Worker construction scope leaked onto the server"));
                })
                .completeIfSuccessful();
    }
}
