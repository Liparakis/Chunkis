package io.liparakis.chunkis.core.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.liparakis.chunkis.core.bits.RangeDecoder;
import io.liparakis.chunkis.core.bits.RangeEncoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Round trips the range coder and the context-modelled dense section coder on many section shapes, and checks a
 * truncated stream is rejected instead of decoding to wrong blocks.
 */
class DenseIndexCoderTest {

    /**
     * Positions per section.
     */
    private static final int VOLUME = 4096;

    /**
     * Codes random bits under skewed probabilities and expects every bit back.
     */
    @Test
    void rangeCoderRoundTripsSkewedBits() {
        final Random random = new Random(1);
        final int[] bits = new int[200_000];
        final int[] contexts = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            contexts[i] = random.nextInt(8);
            bits[i] = random.nextInt(100) < contexts[i] * 12 ? 1 : 0;
        }
        final RangeEncoder encoder = new RangeEncoder();
        final short[] encodeProbabilities = new short[8];
        RangeEncoder.resetProbabilities(encodeProbabilities, 0, 8);
        for (int i = 0; i < bits.length; i++) {
            encoder.encodeBit(encodeProbabilities, contexts[i], bits[i]);
        }
        encoder.finish();

        final RangeDecoder decoder = new RangeDecoder();
        decoder.setData(encoder.buffer(), 0, encoder.size());
        final short[] decodeProbabilities = new short[8];
        RangeEncoder.resetProbabilities(decodeProbabilities, 0, 8);
        for (int i = 0; i < bits.length; i++) {
            assertThat(decoder.decodeBit(decodeProbabilities, contexts[i])).as("bit %d", i).isEqualTo(bits[i]);
        }
        assertThat(decoder.hasOverrun()).isFalse();
    }

    /**
     * Codes sections of every palette size from 2 to 256, in layered, noisy and mixed shapes, several to one
     * stream, and expects every index back.
     */
    @Test
    void sectionsOfEveryShapeRoundTrip() throws IOException {
        final Random random = new Random(7);
        final List<int[]> sections = new ArrayList<>();
        final List<Integer> alphabets = new ArrayList<>();
        for (int alphabet = 2; alphabet <= 256; alphabet++) {
            sections.add(section(random, alphabet, alphabet % 3));
            alphabets.add(alphabet);
        }
        assertRoundTrip(sections, alphabets);
    }

    /**
     * Codes many random sections with random palette sizes and shapes, in random-length streams.
     */
    @Test
    void randomSectionStreamsRoundTrip() throws IOException {
        final Random random = new Random(42);
        for (int stream = 0; stream < 60; stream++) {
            final List<int[]> sections = new ArrayList<>();
            final List<Integer> alphabets = new ArrayList<>();
            final int count = 1 + random.nextInt(8);
            for (int s = 0; s < count; s++) {
                final int alphabet = 2 + random.nextInt(255);
                sections.add(section(random, alphabet, random.nextInt(3)));
                alphabets.add(alphabet);
            }
            assertRoundTrip(sections, alphabets);
        }
    }

    /**
     * Cuts bytes off the end of a coded stream and expects the decode to fail rather than return blocks.
     */
    @Test
    void truncatedStreamIsRejected() {
        final Random random = new Random(3);
        final int[] indices = section(random, 30, 0);
        final RangeEncoder encoder = new RangeEncoder();
        new DenseIndexCoder().encode(encoder, indices, bitsFor(30), 30);
        encoder.finish();
        for (int cut = 1; cut <= Math.min(8, encoder.size()); cut++) {
            final RangeDecoder decoder = new RangeDecoder();
            decoder.setData(encoder.buffer(), 0, encoder.size() - cut);
            assertThatThrownBy(() -> new DenseIndexCoder().decode(decoder, new int[VOLUME], bitsFor(30), 30))
                    .as("stream cut by %d byte(s)", cut)
                    .isInstanceOf(IOException.class);
        }
    }

    /**
     * Codes the sections into one stream, decodes them back and compares.
     */
    private static void assertRoundTrip(final List<int[]> sections, final List<Integer> alphabets)
            throws IOException {
        final RangeEncoder encoder = new RangeEncoder();
        final DenseIndexCoder coder = new DenseIndexCoder();
        for (int s = 0; s < sections.size(); s++) {
            coder.encode(encoder, sections.get(s), bitsFor(alphabets.get(s)), alphabets.get(s));
        }
        encoder.finish();

        final RangeDecoder decoder = new RangeDecoder();
        decoder.setData(encoder.buffer(), 0, encoder.size());
        final DenseIndexCoder decodingCoder = new DenseIndexCoder();
        for (int s = 0; s < sections.size(); s++) {
            final int[] decoded = new int[VOLUME];
            decodingCoder.decode(decoder, decoded, bitsFor(alphabets.get(s)), alphabets.get(s));
            assertThat(decoded).as("section %d of alphabet %d", s, alphabets.get(s))
                    .isEqualTo(sections.get(s));
        }
    }

    /**
     * Generates one section.
     *
     * @param random   source of randomness
     * @param alphabet number of index values
     * @param shape    0 layered terrain with noise, 1 pure noise, 2 runs along x
     * @return indices per position
     */
    private static int[] section(final Random random, final int alphabet, final int shape) {
        final int[] indices = new int[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            indices[i] = switch (shape) {
                case 0 -> random.nextInt(20) == 0 ? random.nextInt(alphabet) : ((i >> 8) * 3) % alphabet;
                case 1 -> random.nextInt(alphabet);
                default -> (i & 15) < 8 ? (i >> 4) % alphabet : random.nextInt(alphabet);
            };
        }
        // Every value 0 .. alphabet - 1 should be reachable; the top one is what out-of-range checks guard.
        indices[VOLUME - 1] = alphabet - 1;
        return Arrays.copyOf(indices, VOLUME);
    }

    /**
     * Bits needed for an index of {@code alphabet} values, as the encoder computes them.
     */
    private static int bitsFor(final int alphabet) {
        return CisDecoder.calculateBitsNeeded(alphabet);
    }
}
