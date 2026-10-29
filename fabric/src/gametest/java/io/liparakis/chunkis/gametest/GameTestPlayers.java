package io.liparakis.chunkis.gametest;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;

/**
 * Creates the server-side mock players that chunk-loading gametests teleport around.
 */
final class GameTestPlayers {

    /**
     * Static holder; not instantiable.
     */
    private GameTestPlayers() {
    }

    /**
     * Creates a creative mock player that is a real {@link ServerPlayerEntity} in the test world,
     * so teleporting it loads and unloads chunks like a joined player.
     *
     * <p>{@link TestContext#createMockPlayer} returns a plain {@code PlayerEntity} that cannot be
     * cast to {@link ServerPlayerEntity}; in 1.21.11 the deprecated factory used here is the only
     * one that returns a server player.</p>
     *
     * @param context gametest context
     * @return mock server player placed in the test world
     */
    @SuppressWarnings("removal")
    static ServerPlayerEntity createServerPlayer(final TestContext context) {
        return context.createMockCreativeServerPlayerInWorld();
    }
}
