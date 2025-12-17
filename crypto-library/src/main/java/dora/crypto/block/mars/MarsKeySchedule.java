package dora.crypto.block.mars;

import dora.crypto.block.KeySchedule;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS key schedule (40 32-bit subkeys).
 *
 * <p>Implements the IBM/NIST "tweaked" key setup (post-Round-1 AES tweak) with the mask-generation step
 * for multiplication subkeys.</p>
 */
final class MarsKeySchedule implements KeySchedule {

    private static final int[] B = {
        0xa4a8d57b, 0x5b5d193b, 0xc8a8309b, 0x73f9a978
    };

    @Override
    public byte[][] roundKeys(byte @NotNull [] key) {
        requireNonNull(key, "key");

        if (key.length < 16 || key.length > 56 || (key.length % 4) != 0) {
            throw new IllegalArgumentException("Key must be 16..56 bytes and a multiple of 4 bytes");
        }

        int n = key.length / 4; // number of 32-bit words in the input key
        int[] k = new int[n];

        // Parse key words in little-endian order (matches the reference implementation)
        for (int i = 0; i < n; i++) {
            int off = i * 4;
            k[i] = (key[off] & 0xff)
                | ((key[off + 1] & 0xff) << 8)
                | ((key[off + 2] & 0xff) << 16)
                | ((key[off + 3] & 0xff) << 24);
        }

        int[] K = expandKey(k, n);

        // Serialize subkeys as little-endian 32-bit words
        byte[][] roundKeys = new byte[40][4];
        for (int i = 0; i < 40; i++) {
            int w = K[i];
            roundKeys[i][0] = (byte) w;
            roundKeys[i][1] = (byte) (w >>> 8);
            roundKeys[i][2] = (byte) (w >>> 16);
            roundKeys[i][3] = (byte) (w >>> 24);
        }

        return roundKeys;
    }

    private static int[] expandKey(int[] k, int n) {
        // T[0..14] scratch array, K[0..39] output
        int[] T = new int[15];
        int[] K = new int[40];

        // Step 1: Initialize T[]
        System.arraycopy(k, 0, T, 0, n);
        T[n] = n;
        for (int i = n + 1; i < 15; i++) {
            T[i] = 0;
        }

        // Step 2: Four iterations, computing 10 words of K[] in each
        for (int j = 0; j < 4; j++) {
            // Step 2a: Linear expansion
            for (int i = 0; i < 15; i++) {
                int i7 = (i + 15 - 7) % 15;
                int i2 = (i + 15 - 2) % 15;
                T[i] = T[i] ^ Integer.rotateLeft(T[i7] ^ T[i2], 3) ^ (4 * i + j);
            }

            // Step 2b: Stirring (4 passes)
            for (int pass = 0; pass < 4; pass++) {
                for (int i = 0; i < 15; i++) {
                    T[i] = Integer.rotateLeft(T[i] + MarsSBox.getSBoxValue(T[(i + 14) % 15] & 0x1ff), 9);
                }
            }

            // Step 2c: Output 10 words into K
            for (int i = 0; i < 10; i++) {
                K[10 * j + i] = T[(4 * i) % 15];
            }
        }

        // Step 3: Modify multiplication subkeys (i = 5,7,...,35)
        for (int i = 5; i <= 35; i += 2) {
            int j = K[i] & 3;        // j = least two bits of K[i]
            int w = K[i] | 3;        // w = K[i] with both low bits set
            int m = generateMask(w); // mask for long constant-bit runs
            int r = K[i - 1] & 0x1f; // r = least five bits of K[i-1]
            int p = Integer.rotateLeft(B[j], r);
            K[i] = w ^ (p & m);
        }

        return K;
    }

    /**
     * Reference mask-generation step (MASK_GEN macro).
     *
     * <p>Produces a mask that selects bits in {@code w} that are part of long runs (length >= 10) of
     * constant bits, excluding the two least significant bits.</p>
     */
    private static int generateMask(int w) {
        int m = ~w ^ (w >>> 1);
        m &= 0x7fffffff;
        m &= (m >>> 1) & (m >>> 2);
        m &= (m >>> 3) & (m >>> 6);

        if (m != 0) {
            m <<= 1;
            m |= (m << 1);
            m |= (m << 2);
            m |= (m << 4);
            m &= 0xfffffffc;
        }

        return m;
    }
}
