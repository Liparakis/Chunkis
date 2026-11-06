package io.liparakis.chunkis.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

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
     * Creates a creative mock player that is a real {@link ServerPlayer} in the test world,
     * so teleporting it loads and unloads chunks like a joined player.
     *
     * <p>{@link GameTestHelper#makeMockPlayer} returns a plain {@code PlayerEntity} that cannot be
     * cast to {@link ServerPlayer}; in 1.21.11 the deprecated factory used here is the only
     * one that returns a server player.</p>
     *
     * @param context gametest context
     * @return mock server player placed in the test world
     */
    @SuppressWarnings("removal")
    static ServerPlayer createServerPlayer(final GameTestHelper context) {
        return context.makeMockServerPlayerInLevel();
    }
}
