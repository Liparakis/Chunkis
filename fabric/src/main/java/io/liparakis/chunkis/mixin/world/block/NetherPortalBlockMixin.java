package io.liparakis.chunkis.mixin.world.block;

import io.liparakis.chunkis.portal.PortalArrivalFallback;
import io.liparakis.chunkis.portal.PortalLinkManager;
import java.util.Optional;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces vanilla fallback portal creation with Chunkis-controlled arrival logic.
 *
 * <p>Vanilla creates a new destination portal when no matching portal POI is found.
 * That is risky for Chunkis because restored portal blocks and POI state may be
 * temporarily out of sync during load/restore flows. This mixin prevents player
 * teleportation from creating surprise portals when vanilla cannot find one.</p>
 *
 * <p>Existing portals are left untouched. If vanilla finds a valid destination
 * portal, the vanilla path continues normally and Chunkis records the link for
 * future direct reuse.</p>
 *
 * <p>This applies only to player travel between the Overworld and Nether. Other
 * entities and non-standard dimensions keep vanilla behavior.</p>
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    /**
     * Default constructor for NetherPortalBlockMixin.
     */
    public NetherPortalBlockMixin() {
    }

    /**
     * Queries vanilla's portal forcer for an existing destination portal.
     *
     * <p>Returns the portal position if one is found, or empty if vanilla would
     * have fallen back to creating a new portal. The caller is responsible for
     * recording the link and deciding whether vanilla should continue.</p>
     *
     * @param destinationWorld     target world to search in
     * @param scaledDestinationPos vanilla-scaled destination position
     * @param destinationIsNether  whether the target world is the Nether
     * @param worldBorder          target world border
     * @return the found destination portal position, or empty
     */
    @Unique
    private static Optional<BlockPos> chunkis$findExistingDestinationPortal(
            final ServerWorld destinationWorld,
            final BlockPos scaledDestinationPos,
            final boolean destinationIsNether,
            final WorldBorder worldBorder
    ) {
        return destinationWorld
                .getPortalForcer()
                .getPortalPos(scaledDestinationPos, destinationIsNether, worldBorder);
    }

    /**
     * Returns whether the source and destination worlds form a vanilla
     * Overworld–Nether portal pair.
     *
     * @param sourceWorld      source world
     * @param destinationWorld destination world
     * @return {@code true} if traveling Overworld → Nether or Nether → Overworld
     */
    @Unique
    private static boolean chunkis$isNetherOverworldPair(
            final World sourceWorld,
            final ServerWorld destinationWorld
    ) {
        final RegistryKey<World> sourceKey = sourceWorld.getRegistryKey();
        final RegistryKey<World> destinationKey = destinationWorld.getRegistryKey();

        return sourceKey == World.NETHER && destinationKey == World.OVERWORLD
                || sourceKey == World.OVERWORLD && destinationKey == World.NETHER;
    }

    /**
     * Intercepts destination portal lookup before vanilla can create a fallback portal.
     *
     * <p>The flow is intentionally conservative:
     * <ol>
     *   <li>Ignore non-player entities.</li>
     *   <li>Ignore non Overworld–Nether transfers.</li>
     *   <li>Use an already-registered Chunkis portal link if one exists.</li>
     *   <li>Let vanilla continue if it can find an existing destination portal
     *       (recording the link for future direct reuse).</li>
     *   <li>Otherwise return a Chunkis fallback teleport target.</li>
     * </ol>
     *
     * @param destinationWorld     target world
     * @param entity               entity using the portal
     * @param sourcePortalPos      source portal position
     * @param scaledDestinationPos vanilla-scaled destination position
     * @param destinationIsNether  whether the destination world is the Nether
     * @param worldBorder          destination world border
     * @param cir                  callback return value
     */
    @Inject(
            method = "getOrCreateExitPortalTarget",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$replaceUnsafePortalCreation(
            final ServerWorld destinationWorld,
            final Entity entity,
            final BlockPos sourcePortalPos,
            final BlockPos scaledDestinationPos,
            final boolean destinationIsNether,
            final WorldBorder worldBorder,
            final CallbackInfoReturnable<TeleportTarget> cir
    ) {
        if (!(entity instanceof ServerPlayerEntity)) {
            return;
        }

        if (!(entity.getEntityWorld() instanceof ServerWorld sourceWorld)) {
            return;
        }

        if (!chunkis$isNetherOverworldPair(sourceWorld, destinationWorld)) {
            return;
        }

        final Optional<TeleportTarget> linkedTarget =
                PortalLinkManager.getLinkedTarget(
                        destinationWorld,
                        entity,
                        sourceWorld,
                        sourcePortalPos
                );

        if (linkedTarget.isPresent()) {
            cir.setReturnValue(linkedTarget.get());
            return;
        }

        final Optional<BlockPos> existingDestination = chunkis$findExistingDestinationPortal(
                destinationWorld, scaledDestinationPos, destinationIsNether, worldBorder
        );

        if (existingDestination.isPresent()) {
            PortalLinkManager.registerBidirectionalIfPresent(
                    sourceWorld, sourcePortalPos, destinationWorld, existingDestination.get()
            );
            // Existing portal found — let vanilla handle teleportation normally.
            return;
        }

        cir.setReturnValue(
                PortalArrivalFallback.create(
                        destinationWorld,
                        entity,
                        sourceWorld,
                        sourcePortalPos,
                        scaledDestinationPos
                )
        );
    }
}
