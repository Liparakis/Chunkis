package io.liparakis.chunkis.command;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurabilityTestCommandTest {

    @Test
    void mapsTeleportTargetToChunkCoordinates() {
        assertEquals(-1, DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9)).x());
        assertEquals(1, DurabilityTestCommand.toChunkKey(new Vec3d(-0.5, 64.0, 31.9)).z());
    }
}
