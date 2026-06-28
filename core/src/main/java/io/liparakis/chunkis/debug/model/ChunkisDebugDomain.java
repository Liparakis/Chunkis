package io.liparakis.chunkis.debug.model;

public enum ChunkisDebugDomain {
    CHUNK_LIFECYCLE,
    DIRTY_TRACKING,
    SAVE_GUARDS,
    LOAD_GUARDS, // TODO: wire load-guard trace boundaries before using this domain
    REGION_STORAGE,
    NBT_SERIALIZATION, // TODO: wire NBT serialization trace boundaries before using this domain
    BASE_CHUNK_CAPTURE, // TODO: wire base-chunk capture trace boundaries before using this domain
    CLIENT_SYNC,
    ASSERTIONS
}
