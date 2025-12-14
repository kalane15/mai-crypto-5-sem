package dora.crypto;

import dora.crypto.SymmetricCipher;
import dora.crypto.block.BlockCipher;
import dora.crypto.block.deal.DealBlockCipher;
import dora.crypto.block.des.DesBlockCipher;
import dora.crypto.block.rc5.Rc5BlockCipher;
import dora.crypto.block.rc5.Rc5Parameters;
import dora.crypto.block.rijndael.RijndaelBlockCipher;
import dora.crypto.block.rijndael.RijndaelParameters;

import java.security.SecureRandom;

public class EncryptionUtil {

    public static BlockCipher createBlockCipher(String algorithm, byte[] key) {
        return switch (algorithm.toUpperCase()) {
            case "DES" -> new DesBlockCipher();
            case "DEAL" -> new DealBlockCipher(key);
            case "RC5" -> {
                // RC5 requires parameters - using defaults: 32-bit words, 12 rounds
                Rc5Parameters params = new Rc5Parameters(
                    Rc5Parameters.WordSize.WORD_SIZE_32,
                    12,
                    key.length
                );
                yield new Rc5BlockCipher(params);
            }
            case "RIJNDAEL" -> {
                // Rijndael requires parameters - using AES-128 as default
                RijndaelParameters params = RijndaelParameters.aes128();
                yield new RijndaelBlockCipher(params);
            }
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        };
    }

    public static SymmetricCipher.CipherModeType getCipherModeType(String mode) {
        return switch (mode.toUpperCase()) {
            case "CBC" -> SymmetricCipher.CipherModeType.CBC;
            case "CFB" -> SymmetricCipher.CipherModeType.CFB;
            case "CTR" -> SymmetricCipher.CipherModeType.CTR;
            case "ECB" -> SymmetricCipher.CipherModeType.ECB;
            case "OFB" -> SymmetricCipher.CipherModeType.OFB;
            case "PCBC" -> SymmetricCipher.CipherModeType.PCBC;
            case "RANDOM_DELTA" -> SymmetricCipher.CipherModeType.RANDOM_DELTA;
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };
    }

    public static SymmetricCipher.PaddingType getPaddingType(String padding) {
        return switch (padding.toUpperCase()) {
            case "ANSI_X923" -> SymmetricCipher.PaddingType.ANSI_X923;
            case "ISO_10126" -> SymmetricCipher.PaddingType.ISO_10126;
            case "PKCS7" -> SymmetricCipher.PaddingType.PKCS7;
            case "ZEROS" -> SymmetricCipher.PaddingType.ZEROS;
            default -> throw new IllegalArgumentException("Unsupported padding: " + padding);
        };
    }

    public static byte[] generateIV(int size) {
        byte[] iv = new byte[size];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public static SymmetricCipher createCipher(String algorithm, String mode, String padding, byte[] key, byte[] iv) {
        BlockCipher blockCipher = createBlockCipher(algorithm, key);
        SymmetricCipher.CipherModeType cipherMode = getCipherModeType(mode);
        SymmetricCipher.PaddingType paddingType = getPaddingType(padding);

        return SymmetricCipher.builder()
                .cipher(blockCipher)
                .mode(cipherMode)
                .padding(paddingType)
                .key(key)
                .iv(iv)
                .build();
    }
}

