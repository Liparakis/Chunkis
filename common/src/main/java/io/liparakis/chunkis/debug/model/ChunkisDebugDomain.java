package io.liparakis.chunkis.debug.model;

public enum ChunkisDebugDomain {
    /**
     * Stores chunk lifecycle.
     */
    CHUNK_LIFECYCLE,
    /**
     * Stores dirty tracking.
     */
    DIRTY_TRACKING,
    /**
     * Stores save guards.
     */
    SAVE_GUARDS,
    /**
     * Stores load guards.
     */
    LOAD_GUARDS, // TODO: wire load-guard trace boundaries before using this domain
    /**
     * Stores region storage.
     */
    REGION_STORAGE,
    /**
     * Stores nbt serialization.
     */
    NBT_SERIALIZATION, // TODO: wire NBT serialization trace boundaries before using this domain
    /**
     * Stores base chunk capture.
     */
    BASE_CHUNK_CAPTURE, // TODO: wire base-chunk capture trace boundaries before using this domain
    /**
     * Stores client sync.
     */
    CLIENT_SYNC,
    /**
     * Stores assertions.
     */
    ASSERTIONS,
    /**
     * Represents this debug domain.
     */
    ENTITY_REPLAY
}
