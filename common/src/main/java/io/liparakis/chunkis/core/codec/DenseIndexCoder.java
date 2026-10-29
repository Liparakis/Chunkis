package io.liparakis.chunkis.core.codec;

import io.liparakis.chunkis.core.bits.RangeDecoder;
import io.liparakis.chunkis.core.bits.RangeEncoder;
import java.io.IOException;

/**
 * Context-modelled coding of one dense section's block indices (CIS v12), in the spirit of JPEG XL's lossless
 * mode: each block is predicted from its neighbours below, west and north, and a range coder spends almost
 * nothing on blocks that match the prediction, which in natural terrain is nearly all of them.
 *
 * <p>Blocks go row by row (16 blocks along x), rows in index order (y, then z). Each row first gets a "the whole
 * row matches the prediction" bit, in a context of whether the previous row and the row below did; natural
 * terrain is mostly such rows, which then cost that one bit. A row that does not match codes its blocks one by
 * one: the predicted value is the west neighbour when it agrees with the north one, else the block below, else
 * west, else north; a "matches the prediction" bit is coded in a context of which neighbours exist and agree.
 * A block that does not match tries the other neighbours (below, west, north, each once and only when different
 * from what was already tried) with one "it is this one" bit each, and only a block matching none of them is
 * coded through an adaptive bit tree selected by the block below. All
 * probabilities start at one half for every section, so sections decode independently of how earlier ones were
 * coded.</p>
 *
 * <p>Encoder and decoder must apply exactly the same predictions, contexts and updates; any change here changes
 * the stored format.</p>
 */
final class DenseIndexCoder {

    /**
     * Positions per section.
     */
    private static final int SECTION_VOLUME = 4096;

    /**
     * Contexts of the "matches the prediction" bit: five neighbour flags.
     */
    private static final int MATCH_CONTEXTS = 32;

    /**
     * Contexts of the "the whole row matches" bit: previous row matched, row below matched, row below exists.
     */
    private static final int ROW_CONTEXTS = 8;

    /**
     * Rows per section.
     */
    private static final int ROWS = 256;

    /**
     * Probabilities of the "matches the prediction" bit, by context.
     */
    private final short[] matchProbabilities = new short[MATCH_CONTEXTS];

    /**
     * Probabilities of the "it is this neighbour" bits after a missed prediction: below, west, north.
     */
    private final short[] neighbourProbabilities = new short[3];

    /**
     * Neighbour values a missed block tries, in order; filled per block.
     */
    private final int[] candidates = new int[3];

    /**
     * Probabilities of the "the whole row matches" bit, by context.
     */
    private final short[] rowProbabilities = new short[ROW_CONTEXTS];

    /**
     * Whether each row of the current section matched entirely.
     */
    private final boolean[] rowMatched = new boolean[ROWS];

    /**
     * Bit-tree probabilities for blocks that miss the prediction: one tree per value of the block below (plus
     * one for no block below), each with one node per tree position.
     */
    private short[] treeProbabilities = new short[0];

    /**
     * Prepares the probabilities for a section.
     *
     * @param bitsPerBlock bits per index, at least 1
     * @param alphabet     number of distinct index values
     * @return tree nodes per context
     */
    private int resetFor(final int bitsPerBlock, final int alphabet) {
        final int nodes = 1 << bitsPerBlock;
        final int needed = (alphabet + 1) * nodes;
        if (treeProbabilities.length < needed) {
            treeProbabilities = new short[needed];
        }
        RangeEncoder.resetProbabilities(matchProbabilities, 0, MATCH_CONTEXTS);
        RangeEncoder.resetProbabilities(rowProbabilities, 0, ROW_CONTEXTS);
        RangeEncoder.resetProbabilities(neighbourProbabilities, 0, neighbourProbabilities.length);
        RangeEncoder.resetProbabilities(treeProbabilities, 0, needed);
        return nodes;
    }

    /**
     * Codes one section's indices.
     *
     * @param encoder      range encoder receiving the section
     * @param indices      index per position, each below {@code alphabet}
     * @param bitsPerBlock bits needed for an index, at least 1
     * @param alphabet     number of distinct index values
     */
    void encode(final RangeEncoder encoder, final int[] indices, final int bitsPerBlock, final int alphabet) {
        final int nodes = resetFor(bitsPerBlock, alphabet);
        for (int row = 0; row < ROWS; row++) {
            final int start = row << 4;
            boolean matched = true;
            for (int i = start; i < start + 16 && matched; i++) {
                matched = indices[i] == predict(indices, i, below(indices, i));
            }
            rowMatched[row] = matched;
            encoder.encodeBit(rowProbabilities, rowContext(row), matched ? 1 : 0);
            if (matched) {
                continue;
            }
            for (int i = start; i < start + 16; i++) {
                final int value = indices[i];
                final int below = below(indices, i);
                final int predicted = predict(indices, i, below);
                if (predicted >= 0) {
                    final int hit = value == predicted ? 1 : 0;
                    encoder.encodeBit(matchProbabilities, matchContext(indices, i, below), hit);
                    if (hit == 1) {
                        continue;
                    }
                }
                final int count = candidates(indices, i, below, predicted);
                boolean found = false;
                for (int c = 0; c < count && !found; c++) {
                    found = value == candidates[c];
                    encoder.encodeBit(neighbourProbabilities, c, found ? 1 : 0);
                }
                if (found) {
                    continue;
                }
                final int base = (below + 1) * nodes;
                int node = 1;
                for (int bit = bitsPerBlock - 1; bit >= 0; bit--) {
                    final int b = (value >>> bit) & 1;
                    encoder.encodeBit(treeProbabilities, base + node, b);
                    node = (node << 1) | b;
                }
            }
        }
    }

    /**
     * Counts the blocks that match their prediction, which tells whether coding the section pays off.
     *
     * @param indices index per position
     * @return number of positions equal to their prediction
     */
    static int countPredicted(final int[] indices) {
        int count = 0;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            if (indices[i] == predict(indices, i, below(indices, i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Decodes one section's indices.
     *
     * @param decoder      range decoder positioned at the section
     * @param indices      receives the index per position
     * @param bitsPerBlock bits needed for an index, at least 1
     * @param alphabet     number of distinct index values
     * @throws IOException if a decoded index is out of range or the stream ran out
     */
    void decode(final RangeDecoder decoder, final int[] indices, final int bitsPerBlock, final int alphabet)
            throws IOException {
        final int nodes = resetFor(bitsPerBlock, alphabet);
        for (int row = 0; row < ROWS; row++) {
            final int start = row << 4;
            final boolean matched = decoder.decodeBit(rowProbabilities, rowContext(row)) == 1;
            rowMatched[row] = matched;
            if (matched) {
                for (int i = start; i < start + 16; i++) {
                    final int predicted = predict(indices, i, below(indices, i));
                    if (predicted < 0) {
                        throw new IOException("Dense section row " + row + " marked predicted without a neighbour");
                    }
                    indices[i] = predicted;
                }
                continue;
            }
            for (int i = start; i < start + 16; i++) {
                final int below = below(indices, i);
                final int predicted = predict(indices, i, below);
                if (predicted >= 0
                        && decoder.decodeBit(matchProbabilities, matchContext(indices, i, below)) == 1) {
                    indices[i] = predicted;
                    continue;
                }
                final int count = candidates(indices, i, below, predicted);
                int chosen = -1;
                for (int c = 0; c < count && chosen < 0; c++) {
                    if (decoder.decodeBit(neighbourProbabilities, c) == 1) {
                        chosen = candidates[c];
                    }
                }
                if (chosen >= 0) {
                    indices[i] = chosen;
                    continue;
                }
                final int base = (below + 1) * nodes;
                int node = 1;
                for (int bit = 0; bit < bitsPerBlock; bit++) {
                    node = (node << 1) | decoder.decodeBit(treeProbabilities, base + node);
                }
                final int value = node - (1 << bitsPerBlock);
                if (value >= alphabet) {
                    throw new IOException("Dense section index " + value + " at position " + i + " is outside its "
                            + alphabet + "-value palette");
                }
                indices[i] = value;
            }
        }
        if (decoder.hasOverrun()) {
            throw new IOException("Dense section block stream truncated");
        }
    }

    /**
     * Lists the neighbour values a block that missed its prediction tries next: below, west and north, each only
     * when present, different from the prediction and from the ones listed before it.
     *
     * @param indices   indices coded so far
     * @param i         position
     * @param below     index below, or -1
     * @param predicted value already tried, or -1
     * @return number of candidates written to {@link #candidates}
     */
    private int candidates(final int[] indices, final int i, final int below, final int predicted) {
        final int west = (i & 15) > 0 ? indices[i - 1] : -1;
        final int north = ((i >> 4) & 15) > 0 ? indices[i - 16] : -1;
        int count = 0;
        if (below >= 0 && below != predicted) {
            candidates[count++] = below;
        }
        if (west >= 0 && west != predicted && west != below) {
            candidates[count++] = west;
        }
        if (north >= 0 && north != predicted && north != below && north != west) {
            candidates[count++] = north;
        }
        return count;
    }

    /**
     * Returns the index below a position, or -1 in the section's bottom layer.
     *
     * @param indices indices coded so far
     * @param i       position
     * @return index below, or -1
     */
    private static int below(final int[] indices, final int i) {
        return (i >> 8) > 0 ? indices[i - 256] : -1;
    }

    /**
     * Context of the "the whole row matches" bit.
     *
     * @param row row index, {@code y * 16 + z}
     * @return context in {@code [0, 8)}
     */
    private int rowContext(final int row) {
        final boolean previous = row > 0 && rowMatched[row - 1];
        final boolean hasBelow = row >= 16;
        final boolean belowMatched = hasBelow && rowMatched[row - 16];
        return (previous ? 1 : 0) | (belowMatched ? 2 : 0) | (hasBelow ? 4 : 0);
    }

    /**
     * Predicts a position's index from its already coded neighbours.
     *
     * @param indices indices coded so far
     * @param i       position
     * @param below   index below, or -1
     * @return predicted index, or -1 when no neighbour is coded yet
     */
    private static int predict(final int[] indices, final int i, final int below) {
        final int west = (i & 15) > 0 ? indices[i - 1] : -1;
        final int north = ((i >> 4) & 15) > 0 ? indices[i - 16] : -1;
        if (west >= 0 && west == north) {
            return west;
        }
        if (below >= 0) {
            return below;
        }
        return west >= 0 ? west : north;
    }

    /**
     * Context of the "matches the prediction" bit: which neighbours exist and which agree.
     *
     * @param indices indices coded so far
     * @param i       position
     * @param below   index below, or -1
     * @return context in {@code [0, 32)}
     */
    private static int matchContext(final int[] indices, final int i, final int below) {
        final int west = (i & 15) > 0 ? indices[i - 1] : -1;
        final int north = ((i >> 4) & 15) > 0 ? indices[i - 16] : -1;
        return (west == north ? 1 : 0) | (below == west ? 2 : 0) | (below == north ? 4 : 0)
                | (below >= 0 ? 8 : 0) | (west >= 0 ? 16 : 0);
    }
}
