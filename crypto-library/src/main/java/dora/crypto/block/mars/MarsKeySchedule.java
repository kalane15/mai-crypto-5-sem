package dora.crypto.block.mars;

import dora.crypto.block.KeySchedule;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS key schedule implementation.
 * MARS supports variable key sizes from 128 to 448 bits (in 32-bit increments).
 */
public final class MarsKeySchedule implements KeySchedule {

    private static final int[] KEY_EXPANSION_CONSTANTS = {
        0x4d34d34d, 0xd34d34d3, 0x34d34d34, 0xd34d34d3
    };

    @Override
    public byte[][] roundKeys(byte @NotNull [] key) {
        requireNonNull(key, "key");

        int keyLength = key.length;
        if (keyLength < 16 || keyLength > 56 || keyLength % 4 != 0) {
            throw new IllegalArgumentException(
                "Key length must be between 128 and 448 bits (16-56 bytes) in 32-bit increments");
        }

        // Convert key to 32-bit words
        int[] keyWords = new int[keyLength / 4];
        for (int i = 0; i < keyWords.length; i++) {
            int offset = i * 4;
            keyWords[i] = ((key[offset] & 0xff) << 24)
                | ((key[offset + 1] & 0xff) << 16)
                | ((key[offset + 2] & 0xff) << 8)
                | (key[offset + 3] & 0xff);
        }

        // Expand key to 40 words (for 32 rounds + 8 pre/post whitening keys)
        int[] expandedKeys = new int[40];
        System.arraycopy(keyWords, 0, expandedKeys, 0, keyWords.length);

        // Key expansion algorithm - fill remaining slots
        int t = keyWords.length;
        int j = 0;
        while (t < 40) {
            for (int i = 0; i < keyWords.length && t < 40; i++) {
                int offset = (i + t - 1) % t;
                int temp = expandedKeys[i] ^ expandedKeys[offset];
                temp = Integer.rotateLeft(temp, 3);
                temp ^= KEY_EXPANSION_CONSTANTS[j % KEY_EXPANSION_CONSTANTS.length];
                temp ^= expandedKeys[(i + t - 2 + t) % t];
                temp = Integer.rotateLeft(temp, (temp & 0x1f));
                expandedKeys[t] = temp;
                t++;
            }
            j++;
        }

        // Convert to byte arrays for round keys (4 bytes each)
        byte[][] roundKeys = new byte[40][4];
        for (int i = 0; i < 40; i++) {
            roundKeys[i][0] = (byte) (expandedKeys[i] >>> 24);
            roundKeys[i][1] = (byte) (expandedKeys[i] >>> 16);
            roundKeys[i][2] = (byte) (expandedKeys[i] >>> 8);
            roundKeys[i][3] = (byte) expandedKeys[i];
        }

        return roundKeys;
    }
}

