# Networking And Client Sync

## What This Area Is

Chunkis sends chunk deltas to clients in addition to vanilla chunk packets. The server encodes Chunkis state, and the client decodes and applies it to the local chunk.

## What Owns It

- server payload registration: `ChunkisMod`
- server send path: `ChunkHolderMixin`, `ChunkisNetworking`, `ChunkDeltaPayload`
- client receive/apply path: `ClientChunkisMod`, `ClientDeltaNetworking`, `ClientDeltaVisitor`

## How It Relates To Other Flows

- restore can trigger resend behavior after authoritative chunk replay
- client sync uses the same `ChunkDelta` model and network codec family from `common`
- networking does not replace vanilla chunk packets; it layers Chunkis state on top

## Key Entry Points

- `fabric/src/main/java/io/liparakis/chunkis/network/ChunkisNetworking.java`
- `fabric/src/main/java/io/liparakis/chunkis/network/ChunkDeltaPayload.java`
- `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaNetworking.java`
- `fabric/src/main/java/io/liparakis/chunkis/mixin/network/ChunkHolderMixin.java`

## Current Flow

1. A vanilla chunk packet is about to be sent.
2. `ChunkHolderMixin` invokes `ChunkisNetworking`.
3. The server extracts the attached delta, encodes it, and wraps it in `ChunkDeltaPayload`.
4. Payloads over `1_024_000` raw bytes are dropped.
5. The client decodes the payload and applies it to the local `WorldChunk` through `ClientDeltaVisitor`.

Vanilla entity tracking remains the source of truth for client entities. Custom Chunkis deltas apply block and block-entity state but do not materialize entity payloads again. Trial spawners are also left to vanilla on the client: their persisted server payload references a dynamic registry that the client cannot decode, while the vanilla chunk/update path provides client-safe state.

## Current Sharp Edges

- client apply assumes the chunk implements `ChunkisDeltaDuck`; failure is logged once and the payload is skipped
- the code proves a size cap, not a guaranteed compression ratio or bandwidth target
- restore-time async resend uses a generation snapshot guard to avoid sending stale payloads

## Where Behavior Is Proven

- `ChunkisNetworkingTest`
- `ChunkDeltaPayloadTest`
- client and server networking classes

## Evidence

- `fabric/src/main/java/io/liparakis/chunkis/ChunkisMod.java`
- `fabric/src/main/java/io/liparakis/chunkis/network/ChunkisNetworking.java`
- `fabric/src/main/java/io/liparakis/chunkis/network/ChunkDeltaPayload.java`
- `fabric/src/main/java/io/liparakis/chunkis/client/ClientDeltaNetworking.java`
- `fabric/src/test/java/io/liparakis/chunkis/network/ChunkisNetworkingTest.java`
- `fabric/src/test/java/io/liparakis/chunkis/network/ChunkDeltaPayloadTest.java`
