package dora.crypto.block.mars;

import dora.crypto.block.BlockCipher;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS block cipher implementation (IBM AES finalist).
 *
 * <p>Block size: 128 bits (16 bytes). Key sizes: 128..448 bits (16..56 bytes) in 32-bit increments.</p>
 *
 * <p>This implementation follows the reference structure used in the IBM submission (mixing phases + 16-round
 * cryptographic core) and matches the official test vectors.</p>
 */
public final class MarsBlockCipher implements BlockCipher {

    private static final int BLOCK_SIZE = 16; // 128 bits = 4 words * 32 bits

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

        // Round keys are 40 32-bit words serialized in little-endian order
        K = new int[40];
        for (int i = 0; i < 40; i++) {
            byte[] w = roundKeys[i];
            K[i] = (w[0] & 0xff)
                | ((w[1] & 0xff) << 8)
                | ((w[2] & 0xff) << 16)
                | ((w[3] & 0xff) << 24);
        }
    }

    @Override
    public byte[] encrypt(byte @NotNull [] plaintext) {
        requireNonNull(plaintext, "plaintext");
        ensureInitialized();
        if (plaintext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Plaintext must be 16 bytes (128 bits)");
        }

        int[] x = bytesToWords(plaintext); // x[0]=a, x[1]=b, x[2]=c, x[3]=d

        // Compute (a,b,c,d) = (a,b,c,d) + (K[0],K[1],K[2],K[3])
        x[0] += K[0];
        x[1] += K[1];
        x[2] += K[2];
        x[3] += K[3];

        // Forwards mixing (8 rounds), in the exact order used by the reference implementation
        fMix(x, 0, 1, 2, 3); x[0] += x[3];
        fMix(x, 1, 2, 3, 0); x[1] += x[2];
        fMix(x, 2, 3, 0, 1);
        fMix(x, 3, 0, 1, 2);

        fMix(x, 0, 1, 2, 3); x[0] += x[3];
        fMix(x, 1, 2, 3, 0); x[1] += x[2];
        fMix(x, 2, 3, 0, 1);
        fMix(x, 3, 0, 1, 2);

        // Cryptographic core (16 rounds)
        core(x, 0, 1, 2, 3, K[4], K[5]);
        core(x, 1, 2, 3, 0, K[6], K[7]);
        core(x, 2, 3, 0, 1, K[8], K[9]);
        core(x, 3, 0, 1, 2, K[10], K[11]);
        core(x, 0, 1, 2, 3, K[12], K[13]);
        core(x, 1, 2, 3, 0, K[14], K[15]);
        core(x, 2, 3, 0, 1, K[16], K[17]);
        core(x, 3, 0, 1, 2, K[18], K[19]);

        // Note the swapped (b,d) roles in the last 8 rounds (matches the spec)
        core(x, 0, 3, 2, 1, K[20], K[21]);
        core(x, 1, 0, 3, 2, K[22], K[23]);
        core(x, 2, 1, 0, 3, K[24], K[25]);
        core(x, 3, 2, 1, 0, K[26], K[27]);
        core(x, 0, 3, 2, 1, K[28], K[29]);
        core(x, 1, 0, 3, 2, K[30], K[31]);
        core(x, 2, 1, 0, 3, K[32], K[33]);
        core(x, 3, 2, 1, 0, K[34], K[35]);

        // Backwards mixing (8 rounds)
        bMix(x, 0, 1, 2, 3);
        bMix(x, 1, 2, 3, 0);
        x[2] -= x[1];
        bMix(x, 2, 3, 0, 1);
        x[3] -= x[0];
        bMix(x, 3, 0, 1, 2);

        bMix(x, 0, 1, 2, 3);
        bMix(x, 1, 2, 3, 0);
        x[2] -= x[1];
        bMix(x, 2, 3, 0, 1);
        x[3] -= x[0];
        bMix(x, 3, 0, 1, 2);

        // Compute (a,b,c,d) = (a,b,c,d) - (K[36],K[37],K[38],K[39])
        x[0] -= K[36];
        x[1] -= K[37];
        x[2] -= K[38];
        x[3] -= K[39];

        return wordsToBytes(x);
    }

    @Override
    public byte[] decrypt(byte @NotNull [] ciphertext) {
        requireNonNull(ciphertext, "ciphertext");
        ensureInitialized();
        if (ciphertext.length != BLOCK_SIZE) {
            throw new IllegalArgumentException("Ciphertext must be 16 bytes (128 bits)");
        }

        int[] x = bytesToWords(ciphertext); // x[0]=a, x[1]=b, x[2]=c, x[3]=d

        // Compute (a,b,c,d) = (a,b,c,d) + (K[36],K[37],K[38],K[39])
        x[0] += K[36];
        x[1] += K[37];
        x[2] += K[38];
        x[3] += K[39];

        // Forwards mixing (8 rounds) - same F_MIX primitive, but reverse-word ordering
        fMix(x, 3, 2, 1, 0); x[3] += x[0];
        fMix(x, 2, 1, 0, 3); x[2] += x[1];
        fMix(x, 1, 0, 3, 2);
        fMix(x, 0, 3, 2, 1);

        fMix(x, 3, 2, 1, 0); x[3] += x[0];
        fMix(x, 2, 1, 0, 3); x[2] += x[1];
        fMix(x, 1, 0, 3, 2);
        fMix(x, 0, 3, 2, 1);

        // Cryptographic core (16 rounds) - inverse
        coreInv(x, 3, 2, 1, 0, K[34], K[35]);
        coreInv(x, 2, 1, 0, 3, K[32], K[33]);
        coreInv(x, 1, 0, 3, 2, K[30], K[31]);
        coreInv(x, 0, 3, 2, 1, K[28], K[29]);
        coreInv(x, 3, 2, 1, 0, K[26], K[27]);
        coreInv(x, 2, 1, 0, 3, K[24], K[25]);
        coreInv(x, 1, 0, 3, 2, K[22], K[23]);
        coreInv(x, 0, 3, 2, 1, K[20], K[21]);

        coreInv(x, 3, 0, 1, 2, K[18], K[19]);
        coreInv(x, 2, 3, 0, 1, K[16], K[17]);
        coreInv(x, 1, 2, 3, 0, K[14], K[15]);
        coreInv(x, 0, 1, 2, 3, K[12], K[13]);
        coreInv(x, 3, 0, 1, 2, K[10], K[11]);
        coreInv(x, 2, 3, 0, 1, K[8], K[9]);
        coreInv(x, 1, 2, 3, 0, K[6], K[7]);
        coreInv(x, 0, 1, 2, 3, K[4], K[5]);

        // Backwards mixing (8 rounds)
        bMix(x, 3, 2, 1, 0);
        bMix(x, 2, 1, 0, 3);
        x[1] -= x[2];
        bMix(x, 1, 0, 3, 2);
        x[0] -= x[3];
        bMix(x, 0, 3, 2, 1);

        bMix(x, 3, 2, 1, 0);
        bMix(x, 2, 1, 0, 3);
        x[1] -= x[2];
        bMix(x, 1, 0, 3, 2);
        x[0] -= x[3];
        bMix(x, 0, 3, 2, 1);

        // Compute (a,b,c,d) = (a,b,c,d) - (K[0],K[1],K[2],K[3])
        x[0] -= K[0];
        x[1] -= K[1];
        x[2] -= K[2];
        x[3] -= K[3];

        return wordsToBytes(x);
    }

    private void ensureInitialized() {
        if (K == null) {
            throw new IllegalStateException("Cipher is not initialized");
        }
    }

    // ===== Primitives (reference-style) =====

    private static int s0(int x) {
        return MarsSBox.getS0(x & 0xff);
    }

    private static int s1(int x) {
        return MarsSBox.getS1(x & 0xff);
    }

    private static int s(int x) {
        return MarsSBox.getSBoxValue(x & 0x1ff);
    }

    /**
     * Forward mixing primitive (F_MIX macro).
     */
    private static void fMix(int[] x, int ia, int ib, int ic, int id) {
        int a = x[ia];
        int b = x[ib];
        int c = x[ic];
        int d = x[id];

        b ^= s0(a);
        b += s1(Integer.rotateRight(a, 8));
        c += s0(Integer.rotateRight(a, 16));
        a = Integer.rotateRight(a, 24);
        d ^= s1(a);

        x[ia] = a;
        x[ib] = b;
        x[ic] = c;
        x[id] = d;
    }

    /**
     * Backward mixing primitive (B_MIX macro).
     */
    private static void bMix(int[] x, int ia, int ib, int ic, int id) {
        int a = x[ia];
        int b = x[ib];
        int c = x[ic];
        int d = x[id];

        b ^= s1(a);
        c -= s0(Integer.rotateLeft(a, 8));
        d -= s1(Integer.rotateLeft(a, 16));
        a = Integer.rotateLeft(a, 24);
        d ^= s0(a);

        x[ia] = a;
        x[ib] = b;
        x[ic] = c;
        x[id] = d;
    }

    /**
     * Core encryption primitive (CORE macro).
     */
    private static void core(int[] x, int ia, int ib, int ic, int id, int k1, int k2) {
        int a = x[ia];
        int b = x[ib];
        int c = x[ic];
        int d = x[id];

        int m = a + k1;
        a = Integer.rotateLeft(a, 13);
        int r = a * k2;
        r = Integer.rotateLeft(r, 5);
        c += Integer.rotateLeft(m, r & 0x1f);
        int l = s(m) ^ r;
        r = Integer.rotateLeft(r, 5);
        l ^= r;
        d ^= r;
        b += Integer.rotateLeft(l, r & 0x1f);

        x[ia] = a;
        x[ib] = b;
        x[ic] = c;
        x[id] = d;
    }

    /**
     * Core decryption primitive (CORE_INV macro).
     */
    private static void coreInv(int[] x, int ia, int ib, int ic, int id, int k1, int k2) {
        int a = x[ia];
        int b = x[ib];
        int c = x[ic];
        int d = x[id];

        int r = a * k2;
        a = Integer.rotateRight(a, 13);
        int m = a + k1;
        r = Integer.rotateLeft(r, 5);
        c -= Integer.rotateLeft(m, r & 0x1f);
        int l = s(m) ^ r;
        r = Integer.rotateLeft(r, 5);
        l ^= r;
        d ^= r;
        b -= Integer.rotateLeft(l, r & 0x1f);

        x[ia] = a;
        x[ib] = b;
        x[ic] = c;
        x[id] = d;
    }

    // ===== Packing / unpacking =====

    private int[] bytesToWords(byte[] bytes) {
        int[] words = new int[4];
        words[0] = (bytes[0] & 0xff)
            | ((bytes[1] & 0xff) << 8)
            | ((bytes[2] & 0xff) << 16)
            | ((bytes[3] & 0xff) << 24);
        words[1] = (bytes[4] & 0xff)
            | ((bytes[5] & 0xff) << 8)
            | ((bytes[6] & 0xff) << 16)
            | ((bytes[7] & 0xff) << 24);
        words[2] = (bytes[8] & 0xff)
            | ((bytes[9] & 0xff) << 8)
            | ((bytes[10] & 0xff) << 16)
            | ((bytes[11] & 0xff) << 24);
        words[3] = (bytes[12] & 0xff)
            | ((bytes[13] & 0xff) << 8)
            | ((bytes[14] & 0xff) << 16)
            | ((bytes[15] & 0xff) << 24);
        return words;
    }

    private byte[] wordsToBytes(int[] words) {
        byte[] bytes = new byte[BLOCK_SIZE];
        bytes[0] = (byte) words[0];
        bytes[1] = (byte) (words[0] >>> 8);
        bytes[2] = (byte) (words[0] >>> 16);
        bytes[3] = (byte) (words[0] >>> 24);
        bytes[4] = (byte) words[1];
        bytes[5] = (byte) (words[1] >>> 8);
        bytes[6] = (byte) (words[1] >>> 16);
        bytes[7] = (byte) (words[1] >>> 24);
        bytes[8] = (byte) words[2];
        bytes[9] = (byte) (words[2] >>> 8);
        bytes[10] = (byte) (words[2] >>> 16);
        bytes[11] = (byte) (words[2] >>> 24);
        bytes[12] = (byte) words[3];
        bytes[13] = (byte) (words[3] >>> 8);
        bytes[14] = (byte) (words[3] >>> 16);
        bytes[15] = (byte) (words[3] >>> 24);
        return bytes;
    }
}
