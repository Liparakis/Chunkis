package io.liparakis.chunkis.world.entity.capture;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPayloadNbtTest {

    @Test
    void readsUuidAndMatchingStateFromEntityPayload() {
        final UUID uuid = UUID.randomUUID();
        final NbtCompound nbt = new NbtCompound();
        nbt.putIntArray("UUID", Uuids.toIntArray(uuid));

        assertEquals(uuid, EntityPayloadNbt.findUuid(nbt).orElseThrow());
        assertTrue(EntityPayloadNbt.hasUuid(nbt, uuid.toString()));
        assertFalse(EntityPayloadNbt.hasUuid(nbt, UUID.randomUUID().toString()));
    }

    @Test
    void readsFlooredBlockPositionFromPosList() {
        final NbtCompound nbt = new NbtCompound();
        final NbtList pos = new NbtList();
        pos.add(NbtDouble.of(1.0D));
        pos.add(NbtDouble.of(64.9D));
        pos.add(NbtDouble.of(-3.2D));
        nbt.put("Pos", pos);

        assertEquals(new BlockPos(1, 64, -4), EntityPayloadNbt.findBlockPos(nbt).orElseThrow());
    }
}
