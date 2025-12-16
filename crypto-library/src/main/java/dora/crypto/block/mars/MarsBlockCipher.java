package dora.crypto.block.mars;

import dora.crypto.block.BlockCipher;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS block cipher implementation.
 * MARS is a Type-3 Feistel network with 128-bit block size and 32 rounds.
 * Key size: 128 to 448 bits (in 32-bit increments).
 */
public final class MarsBlockCipher implements BlockCipher {

    private static final int BLOCK_SIZE = 16; // 128 bits = 4 words of 32 bits each

    private final MarsKeySchedule keySchedule;
    private int[] K; // Expanded key array (40 words)

    public MarsBlockCipher() {
        this.keySchedule = new MarsKeySchedule();
    }

    @Override
    public int blockSize() {
        return BLOCK_SIZE;
    }

    @Override
    public void init(byte @NotNull [] key) {
        requireNonNull(key, "key");
        byte[][] roundKeys = keySchedule.roundKeys(key);
        
        // Convert byte arrays to int array for easier access
        K = new int[40];
        for (int i = 0; i < 40; i++) {
            K[i] = ((roundKeys[i][0] & 0xff) << 24)
                | ((roundKeys[i][1] & 0xff) << 16)
                | ((roundKeys[i][2] & 0xff) << 8)
                | (roundKeys[i][3] & 0xff);
        }
    }

    @Override
    public byte[] encrypt(byte @NotNull [] plaintext) {
        requireNonNull(plaintext, "plaintext");
        if (K == null) {
            throw new IllegalStateException("Cipher is not initialized");
        }
        if (plaintext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Plaintext must be 16 bytes (128 bits)");
        }

        // Convert bytes to 4 words: D = (A, B, C, D)
        int[] D = bytesToWords(plaintext);

        // Forward mixing phase
        forwardMixing(D);

        // Main keyed transformation phase
        mainKeyedTransformation(D);

        // Backward mixing phase
        backwardMixing(D);

        // Convert words back to bytes
        return wordsToBytes(D);
    }

    @Override
    public byte[] decrypt(byte @NotNull [] ciphertext) {
        requireNonNull(ciphertext, "ciphertext");
        if (K == null) {
            throw new IllegalStateException("Cipher is not initialized");
        }
        if (ciphertext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Ciphertext must be 16 bytes (128 bits)");
        }

        // Convert bytes to 4 words
        int[] D = bytesToWords(ciphertext);

        // Phase (III): Backwards mixing (reverse)
        backwardMixingDecrypt(D);

        // Phase (II): Keyed transformation (reverse)
        mainKeyedTransformationDecrypt(D);

        // Phase (I): Forward mixing (reverse)
        forwardMixingDecrypt(D);

        // Convert words back to bytes
        return wordsToBytes(D);
    }

    /**
     * Forward mixing phase (Phase 1) - Encryption.
     * According to pseudo code:
     * (A,B,C,D) = (A,B,C,D) + (K[0],K[1],K[2],K[3])
     * For i = 0 to 7 do {
     *   B = (B ⊕ S0[A]) + S1[A>>>8]
     *   C = C + S0[A>>>16]
     *   D = D ⊕ S1[A>>>24]
     *   A = (A>>>24) + B(if i=1,5) + D(if i=0,4)
     *   (A,B,C,D) = (B,C,D,A)
     * }
     */
    private void forwardMixing(int[] D) {
        // D[0]=A, D[1]=B, D[2]=C, D[3]=D
        // First add subkeys to data
        for (int i = 0; i < 4; i++) {
            D[i] = D[i] + K[i];
        }

        // Eight rounds of forward mixing
        for (int i = 0; i < 8; i++) {
            int A = D[0];
            int B = D[1];
            int C = D[2];
            int D_val = D[3];

            // B = (B ⊕ S0[A]) + S1[A>>>8]
            // In pseudo code, >>> means cyclic shift (rotation)
            B = (B ^ MarsSBox.getS0(A & 0xff)) + MarsSBox.getS1((Integer.rotateRight(A, 8) & 0xff));
            
            // C = C + S0[A>>>16]
            C = C + MarsSBox.getS0((Integer.rotateRight(A, 16) & 0xff));
            
            // D = D ⊕ S1[A>>>24]
            D_val = D_val ^ MarsSBox.getS1((Integer.rotateRight(A, 24) & 0xff));
            
            // A = (A>>>24) + B(if i=1,5) + D(if i=0,4)
            A = Integer.rotateRight(A, 24);
            if (i == 1 || i == 5) {
                A = A + B;
            }
            if (i == 0 || i == 4) {
                A = A + D_val;
            }
            
            // (A,B,C,D) = (B,C,D,A)
            D[0] = B;
            D[1] = C;
            D[2] = D_val;
            D[3] = A;
        }
    }

    /**
     * Main keyed transformation phase (Phase 2) - Encryption.
     * According to pseudo code:
     * for i = 0 to 15 do
     *   (out1; out2; out3) = E-function(D[0]; K[2i+4]; K[2i+5])
     *   D[0] = D[0] <<< 13
     *   D[2] = D[2] + out2
     *   if i < 8 then
     *     D[1] = D[1] + out1
     *     D[3] = D[3] ⊕ out3
     *   else
     *     D[3] = D[3] + out1
     *     D[1] = D[1] ⊕ out3
     *   end-if
     *   (D[3]; D[2]; D[1]; D[0]) ← (D[0]; D[3]; D[2]; D[1])
     * end-for
     */
    private void mainKeyedTransformation(int[] D) {
        for (int i = 0; i < 16; i++) {
            // (out1; out2; out3) = E-function(D[0]; K[2i+4]; K[2i+5])
            EFunctionResult result = eFunction(D[0], K[2 * i + 4], K[2 * i + 5]);
            int out1 = result.L;
            int out2 = result.M;
            int out3 = result.R;

            // D[0] = D[0] <<< 13
            D[0] = Integer.rotateLeft(D[0], 13);

            // D[2] = D[2] + out2
            D[2] = D[2] + out2;

            if (i < 8) {
                // First 8 rounds of forward transformation
                // D[1] = D[1] + out1
                D[1] = D[1] + out1;
                // D[3] = D[3] ⊕ out3
                D[3] = D[3] ^ out3;
            } else {
                // Last 8 rounds of backward transformation
                // D[3] = D[3] + out1
                D[3] = D[3] + out1;
                // D[1] = D[1] ⊕ out3
                D[1] = D[1] ^ out3;
            }

            // Rotate array D[]: (D[3]; D[2]; D[1]; D[0]) ← (D[0]; D[3]; D[2]; D[1])
            int temp = D[0];
            D[0] = D[1];
            D[1] = D[2];
            D[2] = D[3];
            D[3] = temp;
        }
    }

    /**
     * Backward mixing phase (Phase 3) - Encryption.
     * According to pseudo code:
     * For i = 0 to 7 do {
     *   A = A - B(if i=3,7) - D(if i=2,6)
     *   B = B ⊕ S1[A]
     *   C = C - S0[A<<<8]
     *   D = (D - S1[A<<<16]) ⊕ S0[A<<<24]
     *   (A,B,C,D) = (B,C,D,A<<<24)
     * }
     * (A,B,C,D) = (A,B,C,D) - (K[36],K[37],K[38],K[39])
     */
    private void backwardMixing(int[] D) {
        // D[0]=A, D[1]=B, D[2]=C, D[3]=D
        for (int i = 0; i < 8; i++) {
            int A = D[0];
            int B = D[1];
            int C = D[2];
            int D_val = D[3];

            // A = A - B(if i=3,7) - D(if i=2,6)
            if (i == 3 || i == 7) {
                A = A - B;
            }
            if (i == 2 || i == 6) {
                A = A - D_val;
            }

            // B = B ⊕ S1[A]
            B = B ^ MarsSBox.getS1(A & 0xff);

            // C = C - S0[A<<<8]
            int A_rot8 = Integer.rotateLeft(A, 8);
            C = C - MarsSBox.getS0((A_rot8 & 0xff));

            // D = (D - S1[A<<<16]) ⊕ S0[A<<<24]
            int A_rot16 = Integer.rotateLeft(A, 16);
            int A_rot24 = Integer.rotateLeft(A, 24);
            D_val = (D_val - MarsSBox.getS1((A_rot16 & 0xff))) ^ MarsSBox.getS0((A_rot24 & 0xff));

            // (A,B,C,D) = (B,C,D,A<<<24)
            D[0] = B;
            D[1] = C;
            D[2] = D_val;
            D[3] = Integer.rotateLeft(A, 24);
        }

        // (A,B,C,D) = (A,B,C,D) - (K[36],K[37],K[38],K[39])
        for (int i = 0; i < 4; i++) {
            D[i] = D[i] - K[36 + i];
        }
    }

    /**
     * Forward mixing phase (reverse) for decryption - Phase (I).
     * According to pseudo code:
     * for i = 0 to 3 do D[i] = D[i] + K[36 + i]
     * for i = 7 down to 0 do
     *   (D[3], D[2], D[1], D[0]) ← (D[2], D[1], D[0], D[3])
     *   D[0] = D[0] >>> 24
     *   D[3] = D[3] - S0[2nd byte of D[0]]
     *   D[3] = D[3] + S1[3rd byte of D[0]]
     *   D[2] = D[2] + S0[high byte of D[0]]
     *   D[1] = D[1] ^ S1[low byte of D[0]]
     *   if i = 2 or 6 then D[0] = D[0] + D[3]
     *   if i = 3 or 7 then D[0] = D[0] + D[1]
     */
    private void forwardMixingDecrypt(int[] D) {
        // First add subkeys to data (K[36] to K[39])
        for (int i = 0; i < 4; i++) {
            D[i] = D[i] + K[36 + i];
        }

        // Eight rounds of forward mixing in reverse order (i = 7 down to 0)
        for (int i = 7; i >= 0; i--) {
            // Rotate D[] by one word to the left: (D[3], D[2], D[1], D[0]) ← (D[2], D[1], D[0], D[3])
            // This undoes the (A,B,C,D) = (B,C,D,A) from encryption
            int temp = D[3];
            D[3] = D[2];
            D[2] = D[1];
            D[1] = D[0];
            D[0] = temp;

            // Now D[0] is the modified A from encryption (A after >>>24 and additions)
            // We need to undo the additions first
            // Encryption: if i=1 or 5: A = A + B, so decryption: if i=3 or 7: D[0] = D[0] - D[1]
            if (i == 3 || i == 7) {
                D[0] = D[0] - D[1];
            }
            // Encryption: if i=0 or 4: A = A + D, so decryption: if i=2 or 6: D[0] = D[0] - D[3]
            if (i == 2 || i == 6) {
                D[0] = D[0] - D[3];
            }

            // Rotate source word to the left by 24 positions (undo A>>>24 from encryption)
            D[0] = Integer.rotateLeft(D[0], 24);

            // Now D[0] is the original A from encryption (before rotation)
            // Extract bytes from D[0] using cyclic shifts (same as in encryption)
            int lowByte = D[0] & 0xff;              // Lowest byte
            int secondByte = (Integer.rotateRight(D[0], 8) & 0xff);   // Second byte
            int thirdByte = (Integer.rotateRight(D[0], 16) & 0xff);   // Third byte
            int highByte = (Integer.rotateRight(D[0], 24) & 0xff);    // Highest byte

            // Reverse S-box operations (in reverse order of encryption)
            // Encryption: D = D ⊕ S1[A_high], so reverse: D[3] = D[3] ^ S1[highByte]
            D[3] = D[3] ^ MarsSBox.getS1(highByte);
            // Encryption: C = C + S0[A_3rd], so reverse: D[2] = D[2] - S0[thirdByte]
            D[2] = D[2] - MarsSBox.getS0(thirdByte);
            // Encryption: B = (B ⊕ S0[A_low]) + S1[A_2nd], so reverse in reverse order:
            // First undo the addition: D[1] = D[1] - S1[secondByte]
            // Then undo the XOR: D[1] = D[1] ^ S0[lowByte]
            D[1] = D[1] - MarsSBox.getS1(secondByte);
            D[1] = D[1] ^ MarsSBox.getS0(lowByte);
        }
    }

    /**
     * Main keyed transformation phase (reverse) for decryption - Phase (II).
     * Reverses the encryption process:
     * Encryption: E-function, rotate D[0], update D[2], D[1], D[3], rotate array
     * Decryption: undo array rotation, undo D[0] rotation, compute E-function, undo updates
     */
    private void mainKeyedTransformationDecrypt(int[] D) {
        // Sixteen rounds of keyed transformation in reverse order (i = 15 down to 0)
        for (int i = 15; i >= 0; i--) {
            // First, undo the array rotation from encryption
            // Encryption: (D[3], D[2], D[1], D[0]) ← (D[0], D[3], D[2], D[1])
            // Decryption: (D[0], D[3], D[2], D[1]) ← (D[3], D[2], D[1], D[0])
            int temp = D[3];
            D[3] = D[2];
            D[2] = D[1];
            D[1] = D[0];
            D[0] = temp;

            // Now undo D[0] rotation to get the original D[0] value
            // Encryption: D[0] = D[0] <<< 13
            // Decryption: D[0] = D[0] >>> 13
            D[0] = Integer.rotateRight(D[0], 13);

            // Get two key words for this round
            int keyIndex1 = 2 * i + 4;
            int keyIndex2 = 2 * i + 5;
            int key1 = K[keyIndex1];
            int key2 = K[keyIndex2];

            // Compute E-function with the original D[0] value to get outputs
            EFunctionResult result = eFunction(D[0], key1, key2);
            int out1 = result.L;
            int out2 = result.M;
            int out3 = result.R;

            // Reverse the updates to D[1] and D[3]
            if (i < 8) {
                // First 8 rounds: reverse D[1] = D[1] + out1 and D[3] = D[3] ⊕ out3
                D[1] = D[1] - out1;
                D[3] = D[3] ^ out3;
            } else {
                // Last 8 rounds: reverse D[3] = D[3] + out1 and D[1] = D[1] ⊕ out3
                D[3] = D[3] - out1;
                D[1] = D[1] ^ out3;
            }

            // Reverse D[2] = D[2] + out2
            D[2] = D[2] - out2;
        }
    }

    /**
     * E-function (expansion function) for the main keyed transformation.
     * According to encryption pseudo code:
     * R = ((A<<<13) × K[2i+5]) <<< 10
     * M = (A + K[2i+4]) <<< (low 5 bits of (R>>>5))
     * L = (S[M] ⊕ (R>>>5) ⊕ R) <<< (low 5 bits of R)
     */
    private EFunctionResult eFunction(int sourceWord, int key1, int key2) {
        // R = ((A<<<13) × K[2i+5]) <<< 10
        int R = Integer.rotateLeft(sourceWord, 13);
        long product = (long) R * (long) (key2 & 0xffffffffL);
        R = (int) product; // Take lower 32 bits
        R = Integer.rotateLeft(R, 10);

        // M = (A + K[2i+4]) <<< (low 5 bits of (R>>>5))
        int M = sourceWord + key1;
        int rotateAmountM = (R >>> 5) & 0x1f;
        M = Integer.rotateLeft(M, rotateAmountM);

        // L = (S[M] ⊕ (R>>>5) ⊕ R) <<< (low 5 bits of R)
        int sboxIndex = M & 0x1ff; // Low 9 bits of M
        int L = getSBoxValue(sboxIndex);
        L = L ^ (R >>> 5) ^ R;
        int rotateAmountL = R & 0x1f;
        L = Integer.rotateLeft(L, rotateAmountL);

        return new EFunctionResult(L, M, R);
    }

    /**
     * Backward mixing phase (reverse) for decryption - Phase (III).
     * According to pseudo code:
     * for i = 7 down to 0 do
     *   (D[3], D[2], D[1], D[0]) ← (D[2], D[1], D[0], D[3])
     *   if i = 0 or 4 then D[0] = D[0] - D[3]
     *   if i = 1 or 5 then D[0] = D[0] - D[1]
     *   D[0] = D[0] <<< 24
     *   D[3] = D[3] ^ S1[high byte of D[0]]
     *   D[2] = D[2] - S0[3rd byte of D[0]]
     *   D[1] = D[1] - S1[2nd byte of D[0]]
     *   D[1] = D[1] ^ S0[low byte of D[0]]
     * for i = 0 to 3 do D[i] = D[i] - K[i]
     */
    private void backwardMixingDecrypt(int[] D) {
        // Eight rounds of backwards mixing in reverse order (i = 7 down to 0)
        for (int i = 7; i >= 0; i--) {
            // Rotate D[] by one word to the left: (D[3], D[2], D[1], D[0]) ← (D[2], D[1], D[0], D[3])
            int temp = D[3];
            D[3] = D[2];
            D[2] = D[1];
            D[1] = D[0];
            D[0] = temp;

            // Additional mixing operations
            if (i == 0 || i == 4) {
                D[0] = D[0] - D[3];
            }
            if (i == 1 || i == 5) {
                D[0] = D[0] - D[1];
            }

            // Rotate source word to the left by 24 positions
            D[0] = Integer.rotateLeft(D[0], 24);

            // Extract bytes from D[0] using cyclic shifts (consistent with pseudo code)
            int highByte = (Integer.rotateRight(D[0], 24) & 0xff);
            int thirdByte = (Integer.rotateRight(D[0], 16) & 0xff);
            int secondByte = (Integer.rotateRight(D[0], 8) & 0xff);
            int lowByte = D[0] & 0xff;

            // Four S-box look-ups
            D[3] = D[3] ^ MarsSBox.getS1(highByte);
            D[2] = D[2] - MarsSBox.getS0(thirdByte);
            D[1] = D[1] - MarsSBox.getS1(secondByte);
            D[1] = D[1] ^ MarsSBox.getS0(lowByte);
        }

        // Then subtract subkeys from data (K[0] to K[3])
        for (int i = 0; i < 4; i++) {
            D[i] = D[i] - K[i];
        }
    }

    /**
     * Result of E-function containing three output words: L, M, and R.
     */
    private static class EFunctionResult {
        final int L;
        final int M;
        final int R;

        EFunctionResult(int L, int M, int R) {
            this.L = L;
            this.M = M;
            this.R = R;
        }
    }

    /**
     * Gets S-box value for E-function.
     * The S-box is 512 entries (concatenation of S0 and S1).
     * 
     * @param index 9-bit index (0-511)
     * @return 32-bit S-box value
     */
    private int getSBoxValue(int index) {
        // The S-box is the concatenation of S0 and S1
        // For index 0-255: use S0[index]
        // For index 256-511: use S1[index - 256]
        if (index < 256) {
            return MarsSBox.getS0(index);
        } else {
            return MarsSBox.getS1(index - 256);
        }
    }

    /**
     * Converts byte array to 4-word array (32-bit words).
     * Bytes are in little-endian format:
     * - A = bytes[0..3] (little-endian) → words[0]
     * - B = bytes[4..7] → words[1]
     * - C = bytes[8..11] → words[2]
     * - D = bytes[12..15] → words[3]
     */
    private int[] bytesToWords(byte[] bytes) {
        int[] words = new int[4];
        // A (bytes 0-3) in little-endian: LSB first
        words[0] = (bytes[0] & 0xff)
            | ((bytes[1] & 0xff) << 8)
            | ((bytes[2] & 0xff) << 16)
            | ((bytes[3] & 0xff) << 24);
        // B (bytes 4-7) in little-endian
        words[1] = (bytes[4] & 0xff)
            | ((bytes[5] & 0xff) << 8)
            | ((bytes[6] & 0xff) << 16)
            | ((bytes[7] & 0xff) << 24);
        // C (bytes 8-11) in little-endian
        words[2] = (bytes[8] & 0xff)
            | ((bytes[9] & 0xff) << 8)
            | ((bytes[10] & 0xff) << 16)
            | ((bytes[11] & 0xff) << 24);
        // D (bytes 12-15) in little-endian
        words[3] = (bytes[12] & 0xff)
            | ((bytes[13] & 0xff) << 8)
            | ((bytes[14] & 0xff) << 16)
            | ((bytes[15] & 0xff) << 24);
        return words;
    }

    /**
     * Converts 4-word array to byte array.
     * Outputs bytes in little-endian format:
     * - A (words[0]) → bytes[0-3] (little-endian)
     * - B (words[1]) → bytes[4-7]
     * - C (words[2]) → bytes[8-11]
     * - D (words[3]) → bytes[12-15]
     */
    private byte[] wordsToBytes(int[] words) {
        byte[] bytes = new byte[BLOCK_SIZE];
        // A (words[0]) → bytes[0-3] in little-endian: LSB first
        bytes[0] = (byte) words[0];
        bytes[1] = (byte) (words[0] >>> 8);
        bytes[2] = (byte) (words[0] >>> 16);
        bytes[3] = (byte) (words[0] >>> 24);
        // B (words[1]) → bytes[4-7] in little-endian
        bytes[4] = (byte) words[1];
        bytes[5] = (byte) (words[1] >>> 8);
        bytes[6] = (byte) (words[1] >>> 16);
        bytes[7] = (byte) (words[1] >>> 24);
        // C (words[2]) → bytes[8-11] in little-endian
        bytes[8] = (byte) words[2];
        bytes[9] = (byte) (words[2] >>> 8);
        bytes[10] = (byte) (words[2] >>> 16);
        bytes[11] = (byte) (words[2] >>> 24);
        // D (words[3]) → bytes[12-15] in little-endian
        bytes[12] = (byte) words[3];
        bytes[13] = (byte) (words[3] >>> 8);
        bytes[14] = (byte) (words[3] >>> 16);
        bytes[15] = (byte) (words[3] >>> 24);
        return bytes;
    }
}
