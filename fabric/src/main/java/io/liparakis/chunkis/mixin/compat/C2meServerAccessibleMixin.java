package io.liparakis.chunkis.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.liparakis.chunkis.integration.c2me.C2meChunkPromotion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Marks the exact C2ME construction boundary so restoration does not query its own unfinished promotion.
 * C2ME retains its original scheduling, construction and publication logic.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible", remap = false)
public abstract class C2meServerAccessibleMixin {

    /**
     * Not instantiated.
     */
    private C2meServerAccessibleMixin() {
    }

    /**
     * Keeps the scope on this construction thread, and clears it even if C2ME throws.
     *
     * @param proto chunk being promoted
     * @param world owning world
     * @param original C2ME's original conversion
     * @return constructed full chunk
     */
    @WrapMethod(method = "toFullChunk", remap = false, require = 1)
    private static LevelChunk chunkis$constructBeforePublication(final ProtoChunk proto,
            final ServerLevel world, final Operation<LevelChunk> original) {
        final ChunkPos pos = proto.getPos();
        return C2meChunkPromotion.construct(world, pos.x(), pos.z(), () -> original.call(proto, world));
    }
}
