package io.liparakis.chunkis.debug.model;

public enum ChunkTraceReason {
    /** Stores none. */
    NONE,
    /** Stores delta became dirty. */
    DELTA_BECAME_DIRTY,
    /** Stores delta marked saved. */
    DELTA_MARKED_SAVED,
    /** Stores tracker dirty map put. */
    TRACKER_DIRTY_MAP_PUT,
    /** Stores tracker unload cache hit. */
    TRACKER_UNLOAD_CACHE_HIT,
    /** Stores tracker unload cache miss. */
    TRACKER_UNLOAD_CACHE_MISS,
    /** Stores tracker mark saved. */
    TRACKER_MARK_SAVED,
    /** Stores tracker unload cache put. */
    TRACKER_UNLOAD_CACHE_PUT,
    /** Stores tracker unload cache invalidated. */
    TRACKER_UNLOAD_CACHE_INVALIDATED,
    /** Stores tracker chunk unloaded. */
    TRACKER_CHUNK_UNLOADED,
    /** Stores tracker unload cache evict. */
    TRACKER_UNLOAD_CACHE_EVICT,
    /** Stores authoritative delta kept. */
    AUTHORITATIVE_DELTA_KEPT,
    /** Stores stale generation ignored. */
    STALE_GENERATION_IGNORED,
    /** Stores storage write. */
    STORAGE_WRITE,
    /** Stores storage read. */
    STORAGE_READ,
    /** Stores missing region. */
    MISSING_REGION, // TODO: emit when region-level load/store paths start tracing missing-region boundaries
    /** Stores missing entry. */
    MISSING_ENTRY,
    /** Stores tracker memory. */
    TRACKER_MEMORY,
    /** Stores chunkis storage. */
    CHUNKIS_STORAGE,
    /** Stores both. */
    BOTH,
    /** Stores neither. */
    NEITHER,
    /** Stores sparse delta rejected. */
    SPARSE_DELTA_REJECTED,
    /** Stores vanilla storage blocked. */
    VANILLA_STORAGE_BLOCKED, // TODO: emit when vanilla-storage suppression paths are fully traced
    /** Stores empty delta. */
    EMPTY_DELTA,
    /** Stores player unavailable. */
    PLAYER_UNAVAILABLE,
    /** Stores payload too large. */
    PAYLOAD_TOO_LARGE,
    /** Stores invalid payload. */
    INVALID_PAYLOAD,
    /** Stores chunk not delta capable. */
    CHUNK_NOT_DELTA_CAPABLE,
    /** Stores client world unavailable. */
    CLIENT_WORLD_UNAVAILABLE,
    /** Stores decompression failed. */
    DECOMPRESSION_FAILED,
    /** Stores decode failed. */
    DECODE_FAILED,
    /** Stores mapping lookup failed. */
    MAPPING_LOOKUP_FAILED,
    /** Stores off thread mutation rejected. */
    OFF_THREAD_MUTATION_REJECTED,
    /** Stores passive chunk dirtied. */
    PASSIVE_CHUNK_DIRTIED,
    /** Stores suppression context leak. */
    SUPPRESSION_CONTEXT_LEAK,
    /** Stores watched payload trace incomplete. */
    WATCHED_PAYLOAD_TRACE_INCOMPLETE,
    /** Stores decoded payload not applied. */
    DECODED_PAYLOAD_NOT_APPLIED,
    /** Stores decoded payload not consumed by world constructor. */
    DECODED_PAYLOAD_NOT_CONSUMED_BY_WORLD_CONSTRUCTOR,
    /** Stores decoded payload not visited by restore. */
    DECODED_PAYLOAD_NOT_VISITED_BY_RESTORE,
    /** Stores restore setblock did not apply. */
    RESTORE_SETBLOCK_DID_NOT_APPLY,
    /** Stores restore applied to different chunk instance. */
    RESTORE_APPLIED_TO_DIFFERENT_CHUNK_INSTANCE,
    /** Stores watched entity unloaded not reloaded. */
    WATCHED_ENTITY_UNLOADED_NOT_RELOADED,
    /** Stores player or command edit. */
    PLAYER_OR_COMMAND_EDIT,
    /** Stores explicit chunkis mutation. */
    EXPLICIT_CHUNKIS_MUTATION,
    /** Stores restore of existing chunkis storage. */
    RESTORE_OF_EXISTING_CHUNKIS_STORAGE,
    /** Stores base capture for existing chunkis chunk. */
    BASE_CAPTURE_FOR_EXISTING_CHUNKIS_CHUNK, // TODO: emit when existing-Chunkis base-capture boundaries are traced
    /** Stores migration of existing chunkis data. */
    MIGRATION_OF_EXISTING_CHUNKIS_DATA, // TODO: emit when legacy Chunkis migration boundaries are traced
    /** Stores passive vanilla load. */
    PASSIVE_VANILLA_LOAD,
    /** Stores vanilla generation. */
    VANILLA_GENERATION, // TODO: emit when vanilla generation boundaries are traced
    /** Stores vanilla population. */
    VANILLA_POPULATION, // TODO: emit when vanilla population boundaries are traced
    /** Stores teleport only load unload. */
    TELEPORT_ONLY_LOAD_UNLOAD, // TODO: emit when teleport-only load/unload boundaries are traced
    /** Stores chunk send to client. */
    CHUNK_SEND_TO_CLIENT, // TODO: emit when client-send boundaries are traced
    /** Stores vanilla autosave untouched. */
    VANILLA_AUTOSAVE_UNTOUCHED,
    /** Stores internal restore base apply. */
    INTERNAL_RESTORE_BASE_APPLY,
    /** Stores vanilla cancelled without ownership. */
    VANILLA_CANCELLED_WITHOUT_OWNERSHIP, // TODO: emit when ownershipless vanilla-cancel boundaries are traced
    /** Stores save without ownership. */
    SAVE_WITHOUT_OWNERSHIP,
    /** Stores reset empty without ownership. */
    RESET_EMPTY_WITHOUT_OWNERSHIP,
    /** Stores base snapshot not applied. */
    BASE_SNAPSHOT_NOT_APPLIED,
    /** Stores restore empty result. */
    RESTORE_EMPTY_RESULT,
    /** Stores restore exception. */
    RESTORE_EXCEPTION,
    /** Stores proto delta not restored. */
    PROTO_DELTA_NOT_RESTORED,
    /** Stores io exception. */
    IO_EXCEPTION,
    /** Represents this trace reason. */
    ENTITY_REPLAY
}
