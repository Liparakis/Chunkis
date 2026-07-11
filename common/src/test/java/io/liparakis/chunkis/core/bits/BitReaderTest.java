package io.liparakis.chunkis.core.bits;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class BitReaderTest {

    /** Performs test read batch aligned. */
    @Test
    public void testReadBatchAligned() {
        byte[] data = {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0x12};
        BitReader reader1 = new BitReader(data);
        BitReader reader2 = new BitReader(data);

        int[] batch = new int[8];
        reader1.readBatch(4, batch);

        int[] expected = new int[8];
        for (int i = 0; i < 8; i++) {
            expected[i] = (int) reader2.read(4);
        }

        assertThat(batch).containsExactly(expected);
    }

    /** Performs test read batch unaligned. */
    @Test
    public void testReadBatchUnaligned() {
        byte[] data = {(byte) 0b11010101, (byte) 0b10111000, (byte) 0b01111111};
        BitReader reader1 = new BitReader(data);
        BitReader reader2 = new BitReader(data);

        int[] batch = new int[6];
        reader1.readBatch(3, batch);

        int[] expected = new int[6];
        for (int i = 0; i < 6; i++) {
            expected[i] = (int) reader2.read(3);
        }

        assertThat(batch).containsExactly(expected);
    }

    /** Performs test read batch mixed. */
    @Test
    public void testReadBatchMixed() {
        byte[] data = {(byte) 0xFF, (byte) 0x00, (byte) 0xAA, (byte) 0x55, (byte) 0x12, (byte) 0x34};

        for (int bits = 1; bits <= 16; bits++) {
            BitReader reader1 = new BitReader(data);
            BitReader reader2 = new BitReader(data);

            // Read a few bits first to test unaligned start
            reader1.read(3);
            reader2.read(3);

            int count = (data.length * 8 - 3) / bits;
            int[] batch = new int[count];
            reader1.readBatch(bits, batch);

            int[] expected = new int[count];
            for (int i = 0; i < count; i++) {
                expected[i] = (int) reader2.read(bits);
            }

            assertThat(batch)
                    .as("Mismatch at bits=" + bits)
                    .containsExactly(expected);
        }
    }

    /** Performs test read batch zero bits. */
    @Test
    public void testReadBatchZeroBits() {
        byte[] data = {(byte) 0xFF};
        BitReader reader = new BitReader(data);
        int[] batch = new int[5];
        reader.readBatch(0, batch);
        assertThat(batch).containsExactly(0, 0, 0, 0, 0);
    }

    /** Performs test read batch end of stream. */
    @Test
    public void testReadBatchEndOfStream() {
        byte[] data = {(byte) 0xFF};
        BitReader reader = new BitReader(data);
        int[] batch = new int[4];
        reader.readBatch(4, batch); // consumes 2 entries of 4 bits
        assertThat(batch[0]).isEqualTo(15);
        assertThat(batch[1]).isEqualTo(15);
        assertThat(batch[2]).isEqualTo(0);
        assertThat(batch[3]).isEqualTo(0);
    }
}
