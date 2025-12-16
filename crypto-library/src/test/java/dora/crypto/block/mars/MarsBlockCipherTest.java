package dora.crypto.block.mars;

import dora.crypto.block.BlockCipher;
import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;

import static org.assertj.core.api.Assertions.assertThat;

public class MarsBlockCipherTest {

    private final BlockCipher blockCipher;

    MarsBlockCipherTest() {
        blockCipher = new MarsBlockCipher();
    }

    @Example
    void testKnownVector128BitKey() {
        // Test vector from MARS specification
        // KEYSIZE=128
        // I=1
        // KEY=00000000000000000000000000000000
        // PT=00000000000000000000000000000000
        // CT=DCC07B8DFB0738D6E30A22DFCF27E886
        byte[] key = hexStringToByteArray("00000000000000000000000000000000");
        byte[] plaintext = hexStringToByteArray("00000000000000000000000000000000");
        byte[] expectedCiphertext = hexStringToByteArray("DCC07B8DFB0738D6E30A22DFCF27E886");

        blockCipher.init(key);
        byte[] ciphertext = blockCipher.encrypt(plaintext);
        byte[] decrypted = blockCipher.decrypt(ciphertext);

        assertThat(ciphertext).isEqualTo(expectedCiphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    @Property(tries = 1000)
    void decryptedCiphertextEqualsPlaintext(
        @ForAll @Size(value = 16) byte[] plaintext,
        @ForAll @Size(min = 16, max = 56) byte[] key
    ) {
        // Ensure key length is a multiple of 4 (32-bit increments)
        int keyLength = key.length;
        if (keyLength % 4 != 0) {
            keyLength = (keyLength / 4) * 4;
            if (keyLength < 16) {
                keyLength = 16;
            }
            if (keyLength > 56) {
                keyLength = 56;
            }
        }
        byte[] validKey = new byte[keyLength];
        System.arraycopy(key, 0, validKey, 0, Math.min(key.length, keyLength));

        blockCipher.init(validKey);

        byte[] encrypted = blockCipher.encrypt(plaintext);
        byte[] decrypted = blockCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Property(tries = 100)
    void decryptedCiphertextEqualsPlaintextWith128BitKey(
        @ForAll @Size(value = 16) byte[] plaintext,
        @ForAll @Size(value = 16) byte[] key
    ) {
        blockCipher.init(key);

        byte[] encrypted = blockCipher.encrypt(plaintext);
        byte[] decrypted = blockCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Property(tries = 100)
    void decryptedCiphertextEqualsPlaintextWith192BitKey(
        @ForAll @Size(value = 16) byte[] plaintext,
        @ForAll @Size(value = 24) byte[] key
    ) {
        blockCipher.init(key);

        byte[] encrypted = blockCipher.encrypt(plaintext);
        byte[] decrypted = blockCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Property(tries = 100)
    void decryptedCiphertextEqualsPlaintextWith256BitKey(
        @ForAll @Size(value = 16) byte[] plaintext,
        @ForAll @Size(value = 32) byte[] key
    ) {
        blockCipher.init(key);

        byte[] encrypted = blockCipher.encrypt(plaintext);
        byte[] decrypted = blockCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }
}

