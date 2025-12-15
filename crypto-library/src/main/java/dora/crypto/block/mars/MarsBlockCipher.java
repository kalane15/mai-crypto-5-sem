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

    private static final int BLOCK_SIZE = 16; // 128 bits
    private static final int ROUNDS = 32;

    private final MarsKeySchedule keySchedule;
    private final MarsRoundFunction roundFunction;
    private byte[][] roundKeys;

    public MarsBlockCipher() {
        this.keySchedule = new MarsKeySchedule();
        this.roundFunction = new MarsRoundFunction();
    }

    @Override
    public int blockSize() {
        return BLOCK_SIZE;
    }

    @Override
    public void init(byte @NotNull [] key) {
        requireNonNull(key, "key");
        roundKeys = keySchedule.roundKeys(key);
    }

    @Override
    public byte[] encrypt(byte @NotNull [] plaintext) {
        requireNonNull(plaintext, "plaintext");

        if (roundKeys == null) {
            throw new IllegalStateException("Cipher is not initialized");
        }
        if (plaintext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Plaintext must be 16 bytes (128 bits)");
        }

        // Split block into 4 words (32 bits each)
        int[] words = bytesToWords(plaintext);

        // Pre-whitening: add first 4 round keys
        for (int i = 0; i < 4; i++) {
            words[i] += getKeyWord(roundKeys[i]);
        }

        // 32 rounds (Type-3 Feistel: operates on all 4 words)
        // Use left half (words[0], words[1]) and right half (words[2], words[3])
        for (int round = 0; round < ROUNDS; round++) {
            int keyIndex = 4 + round;

            // Apply round function to RIGHT half (words[2]) - this makes decryption easier
            // Standard Feistel: fValue = f(old_right, key), then swap and XOR
            byte[] blockBytes = intToBytes(words[2]);
            byte[] keyBytes = roundKeys[keyIndex];
            byte[] fResult = roundFunction.apply(blockBytes, keyBytes);
            int fValue = bytesToInt(fResult);

            // Standard Feistel: new_left = old_right, new_right = old_left ^ f(old_right, key)
            int temp0 = words[0]; // old_left
            int temp1 = words[1];
            words[0] = words[2]; // new_left = old_right
            words[1] = words[3];
            words[2] = temp0 ^ fValue; // new_right = old_left ^ f(old_right, key)
            words[3] = temp1 ^ Integer.rotateLeft(fValue, 16);
        }

        // Post-whitening: subtract last 4 round keys
        for (int i = 0; i < 4; i++) {
            int keyIndex = 36 + i;
            words[i] -= getKeyWord(roundKeys[keyIndex]);
        }

        return wordsToBytes(words);
    }

    @Override
    public byte[] decrypt(byte @NotNull [] ciphertext) {
        requireNonNull(ciphertext, "ciphertext");

        if (roundKeys == null) {
            throw new IllegalStateException("Cipher is not initialized");
        }
        if (ciphertext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Ciphertext must be 16 bytes (128 bits)");
        }

        // Split block into 4 words (32 bits each)
        int[] words = bytesToWords(ciphertext);

        // Post-whitening (reverse): add last 4 round keys
        for (int i = 0; i < 4; i++) {
            int keyIndex = 36 + i;
            words[i] += getKeyWord(roundKeys[keyIndex]);
        }

        // 32 rounds in reverse order
        for (int round = ROUNDS - 1; round >= 0; round--) {
            int keyIndex = 4 + round;

            // Reverse Feistel: in encryption, new_left = old_right, new_right = old_left ^ f(old_right, key)
            // So: old_right = new_left, old_left = new_right ^ f(old_right, key) = new_right ^ f(new_left, key)
            
            // Compute fValue from current left half (which is old_right from encryption)
            // This matches encryption where we computed fValue from old_right (words[2])
            byte[] blockBytes = intToBytes(words[0]);
            byte[] keyBytes = roundKeys[keyIndex];
            byte[] fResult = roundFunction.apply(blockBytes, keyBytes);
            int fValue = bytesToInt(fResult);

            // Reverse: old_left = new_right ^ f(new_left, key), old_right = new_left
            int temp0 = words[0]; // new_left = old_right
            int temp1 = words[1];
            words[0] = words[2] ^ fValue; // old_left = new_right ^ f(new_left, key)
            words[1] = words[3] ^ Integer.rotateLeft(fValue, 16);
            words[2] = temp0; // old_right = new_left
            words[3] = temp1;
        }

        // Pre-whitening (reverse): subtract first 4 round keys
        for (int i = 0; i < 4; i++) {
            words[i] -= getKeyWord(roundKeys[i]);
        }

        return wordsToBytes(words);
    }

    private int getKeyWord(byte[] keyBytes) {
        return ((keyBytes[0] & 0xff) << 24)
            | ((keyBytes[1] & 0xff) << 16)
            | ((keyBytes[2] & 0xff) << 8)
            | (keyBytes[3] & 0xff);
    }

    private int[] bytesToWords(byte[] bytes) {
        int[] words = new int[4];
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            words[i] = ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
        }
        return words;
    }

    private byte[] wordsToBytes(int[] words) {
        byte[] bytes = new byte[BLOCK_SIZE];
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            bytes[offset] = (byte) (words[i] >>> 24);
            bytes[offset + 1] = (byte) (words[i] >>> 16);
            bytes[offset + 2] = (byte) (words[i] >>> 8);
            bytes[offset + 3] = (byte) words[i];
        }
        return bytes;
    }

    private byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        };
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xff) << 24)
            | ((bytes[1] & 0xff) << 16)
            | ((bytes[2] & 0xff) << 8)
            | (bytes[3] & 0xff);
    }
}
