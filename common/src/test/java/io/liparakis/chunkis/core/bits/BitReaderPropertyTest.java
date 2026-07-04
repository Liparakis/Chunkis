package io.liparakis.chunkis.core.bits;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class BitReaderPropertyTest {

    @Test
    public void testRandomProperties() {
        Random random = new Random(42);

        for (int run = 0; run < 10000; run++) {
            int dataLength = random.nextInt(100) + 5;
            byte[] data = new byte[dataLength];
            random.nextBytes(data);

            int startBit = random.nextInt(8);
            int bits = random.nextInt(16) + 1;

            // Calculate max count we can read
            int totalBits = dataLength * 8 - startBit;
            int count = random.nextInt(totalBits / bits + 1);
            if (count == 0) {
                count = 1;
            }

            BitReader reader1 = new BitReader(data);
            BitReader reader2 = new BitReader(data);

            // Set startBit position
            if (startBit > 0) {
                reader1.read(startBit);
                reader2.read(startBit);
            }

            int[] batch = new int[count];
            reader1.readBatch(bits, batch);

            int[] expected = new int[count];
            for (int i = 0; i < count; i++) {
                expected[i] = (int) reader2.read(bits);
            }

            // Verify the output array matches
            assertThat(batch)
                    .as("Run %d failed: bits=%d, count=%d, startBit=%d", run, bits, count, startBit)
                    .containsExactly(expected);

            // Verify the final reader state (byteIndex and bitIndex) matches
            // We use reflection or expose packages, but wait, we can check reader.read(1) from both!
            for (int k = 0; k < 10; k++) {
                long r1 = reader1.read(1);
                long r2 = reader2.read(1);
                assertThat(r1)
                        .as("Post-read mismatch at run %d, step %d", run, k)
                        .isEqualTo(r2);
            }
        }
    }
}
