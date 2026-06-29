package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * Encapsulates the primitive array and fastutil map used to store
 * and query packed block instructions within a ChunkDelta.
 */
final class BlockInstructionStorage {

    private static final int INITIAL_CAPACITY = 64;
    final Long2IntOpenHashMap positionMap;
    long[] packedInstructions;
    int instructionCount;

    BlockInstructionStorage() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1);
    }

    BlockInstructionStorage(final Long2IntOpenHashMap positionMap) {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.positionMap = positionMap;
    }

    BlockInstructionStorage copy() {
        final BlockInstructionStorage copy = new BlockInstructionStorage(this.positionMap.clone());
        copy.packedInstructions = Arrays.copyOf(this.packedInstructions, this.packedInstructions.length);
        copy.instructionCount = this.instructionCount;
        return copy;
    }

    void clear() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.positionMap.clear();
    }

    void ensureCapacity() {
        if (instructionCount < packedInstructions.length) {
            return;
        }
        packedInstructions = Arrays.copyOf(packedInstructions, packedInstructions.length << 1);
    }

    void ensureBlockCapacity(final int additionalBlocks) {
        if (additionalBlocks <= 0) {
            return;
        }
        final int requiredCapacity = instructionCount + additionalBlocks;
        if (requiredCapacity > packedInstructions.length) {
            int newCapacity = packedInstructions.length;
            while (newCapacity < requiredCapacity) {
                newCapacity <<= 1;
            }
            packedInstructions = Arrays.copyOf(packedInstructions, newCapacity);
        }
        positionMap.ensureCapacity(requiredCapacity);
    }

    void add(final long instruction, final long posKey) {
        ensureCapacity();
        packedInstructions[instructionCount] = instruction;
        positionMap.put(posKey, instructionCount);
        instructionCount++;
    }

    void update(final int index, final long newInstruction) {
        packedInstructions[index] = newInstruction;
    }
}
