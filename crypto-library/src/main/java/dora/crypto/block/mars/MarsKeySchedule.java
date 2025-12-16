package dora.crypto.block.mars;

import dora.crypto.block.KeySchedule;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS key schedule implementation.
 * MARS supports variable key sizes from 128 to 448 bits (in 32-bit increments).
 * 
 * Key expansion procedure expands a key array of n 32-bit words (4 <= n <= 14)
 * into an array K[] of 40 words.
 */
public final class MarsKeySchedule implements KeySchedule {

    // Fixed table B for modifying multiplication keys
    // These are entries 265-268 in the S-box
    private static final int[] B = {
        0xa4a8d57b, 0x5b5d193b, 0xc8a8309b, 0x73f9a978
    };

    @Override
    public byte[][] roundKeys(byte @NotNull [] key) {
        requireNonNull(key, "key");

        int keyLength = key.length;
        if (keyLength < 16 || keyLength > 56 || keyLength % 4 != 0) {
            throw new IllegalArgumentException(
                "Key length must be between 128 and 448 bits (16-56 bytes) in 32-bit increments");
        }

        int n = keyLength / 4; // Number of 32-bit words in key (4 <= n <= 14)

        // Convert key bytes to 32-bit words
        int[] k = new int[n];
        for (int i = 0; i < n; i++) {
            int offset = i * 4;
            k[i] = ((key[offset] & 0xff) << 24)
                | ((key[offset + 1] & 0xff) << 16)
                | ((key[offset + 2] & 0xff) << 8)
                | (key[offset + 3] & 0xff);
        }

        // Expand key to 40 words
        int[] K = expandKey(k, n);

        // Convert to byte arrays (4 bytes each)
        byte[][] roundKeys = new byte[40][4];
        for (int i = 0; i < 40; i++) {
            roundKeys[i][0] = (byte) (K[i] >>> 24);
            roundKeys[i][1] = (byte) (K[i] >>> 16);
            roundKeys[i][2] = (byte) (K[i] >>> 8);
            roundKeys[i][3] = (byte) K[i];
        }

        return roundKeys;
    }

    /**
     * Expands the key according to MARS specification.
     * @param k Input key array (n words)
     * @param n Number of words in key (4 <= n <= 14)
     * @return Expanded key array (40 words)
     */
    private int[] expandKey(int[] k, int n) {
        int[] K = new int[40];
        int[] T = new int[15];

        // Step 1: Initialize T[]
        // T[0..n-1] = k[0..n-1], T[n] = n, T[n+1..14] = 0
        System.arraycopy(k, 0, T, 0, n);
        T[n] = n;
        for (int i = n + 1; i < 15; i++) {
            T[i] = 0;
        }

        // Step 2: Four iterations, computing 10 words of K[] in each
        for (int j = 0; j < 4; j++) {
            // Step 2a: Linear Key-Word Expansion
            // T[i] = T[i] ⊕ ((T[i-7 mod 15] ⊕ T[i-2 mod 15]) <<< 3) ⊕ (4i+j)
            for (int i = 0; i < 15; i++) {
                int i7 = (i - 7 + 15) % 15;
                int i2 = (i - 2 + 15) % 15;
                T[i] = T[i] ^ Integer.rotateLeft(T[i7] ^ T[i2], 3) ^ (4 * i + j);
            }

            // Step 2b: S-box Based Stirring of Key-Words
            // Repeat 4 times { For i = 0, 1, … ,14 do { T[i] = (T[i] + S[low 9 bits of T[i-1 mod 15]]) <<< 9 } }
            for (int round = 0; round < 4; round++) {
                for (int i = 0; i < 15; i++) {
                    int i1 = (i - 1 + 15) % 15;
                    int sboxIndex = T[i1] & 0x1ff; // Low 9 bits
                    int sboxValue = getSBoxValue(sboxIndex);
                    T[i] = Integer.rotateLeft(T[i] + sboxValue, 9);
                }
            }

            // Step 2c: Store Next 10 Key-Words into K[]
            // For i = 0, 1, … ,9 do { K[10j+i] = T[4i mod 15] }
            for (int i = 0; i < 10; i++) {
                K[10 * j + i] = T[(4 * i) % 15];
            }
        }

        // Step 3: Modifying Multiplication Key-Words
        // For i = 5, 7, … ,35 do {
        for (int i = 5; i <= 35; i += 2) {
            // j = least two bits of K[i]
            int j = K[i] & 3;
            
            // w = K[i] with both of the lowest two bits set to 1
            int w = K[i] | 3;
            
            // Compute a word mask M
            int M = generateMask(w);
            
            // r = least five bits of K[i-1]
            int r = K[i - 1] & 0x1f;
            
            // p = B[j] <<< r
            int p = Integer.rotateLeft(B[j], r);
            
            // K[i] = w ⊕ (p ∧ M)
            K[i] = w ^ (p & M);
        }

        return K;
    }

    /**
     * Generates a bit mask M according to the pseudo code:
     * M = 0
     * M[n] = 1 iff w[n] belongs to a sequence of 10 consecutive 0's or 1's in w,
     * and also 2 ≤ n ≤ 30 and w[n-1] = w[n] = w[n+1]
     */
    private int generateMask(int w) {
        int M = 0;

        // For each bit position n from 2 to 30
        for (int n = 2; n <= 30; n++) {
            int bitN = (w >>> n) & 1;
            int bitNMinus1 = (w >>> (n - 1)) & 1;
            int bitNPlus1 = (w >>> (n + 1)) & 1;
            
            // Check condition: w[n-1] = w[n] = w[n+1]
            if (bitNMinus1 == bitN && bitN == bitNPlus1) {
                // Check if this bit belongs to a sequence of 10 consecutive 0's or 1's
                if (isInLongSequence(w, n)) {
                    M |= (1 << n);
                }
            }
        }

        return M;
    }

    /**
     * Checks if bit at position i is part of a sequence of 10+ consecutive 0's or 1's.
     */
    private boolean isInLongSequence(int w, int i) {
        int bit = (w >>> i) & 1;
        
        // Count consecutive bits of same value to the left
        int leftCount = 0;
        for (int j = i - 1; j >= 0; j--) {
            if (((w >>> j) & 1) == bit) {
                leftCount++;
            } else {
                break;
            }
        }
        
        // Count consecutive bits of same value to the right
        int rightCount = 0;
        for (int j = i + 1; j < 32; j++) {
            if (((w >>> j) & 1) == bit) {
                rightCount++;
            } else {
                break;
            }
        }
        
        // Total sequence length including current bit
        int totalLength = leftCount + 1 + rightCount;
        return totalLength >= 10;
    }

    /**
     * Gets S-box value for key expansion.
     * Uses the actual MARS S-box (512 entries).
     */
    private int getSBoxValue(int index) {
        return MarsSBox.getSBoxValue(index);
    }
}

