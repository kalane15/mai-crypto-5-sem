package dora.crypto.block.mars;

import dora.crypto.block.RoundFunction;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * MARS round function (E-function) implementation.
 * This implements the core mixing function used in MARS rounds.
 */
public final class MarsRoundFunction implements RoundFunction {

    // MARS S-box (9-bit to 32-bit lookup)
    private static final int[] S_BOX = {
        0x09d0c479, 0x28c8ffe0, 0x84aa6c39, 0x9dad7287, 0x7dff9be3, 0xd4268361,
        0xc96da1d4, 0x79e2a9e3, 0x06d48bf7, 0x6cc6a4a7, 0xe417f38a, 0x179befc2,
        0x4c9a5fcc, 0xdd7e1048, 0x6753f927, 0x622d5d49, 0x8c712b10, 0x3f4c1ba7,
        0x9e7d3064, 0x07d7dfe8, 0x5f47a020, 0x6ba8444f, 0x83f3a70b, 0x1fc0a3d0,
        0x8b59c26b, 0x31fdc0c6, 0xbfec04ce, 0x29b44258, 0x9da471f9, 0x2e312110,
        0x530ed8a8, 0x8b2e1ce8, 0xafc4d550, 0x09aef60d, 0x88d43305, 0x5cc06092,
        0xbf7e34b7, 0x5b522431, 0x89246f50, 0x438296db, 0x18b703da, 0x7dbe8056,
        0x1eff36e6, 0x21356fe3, 0x58979c4f, 0x90f86f0b, 0x201d4fdb, 0x1f9e25d7,
        0x6d4e0272, 0x90fd0973, 0xf65fdec4, 0x038ebe5b, 0x877f92e2, 0x08419c26,
        0x637b2c34, 0x0e678226, 0xb945910e, 0x2819205e, 0xfc4478f0, 0x0bbcd95f,
        0x9801b893, 0x7071bf93, 0xfc8c5b97, 0x173b3b04, 0x2c818445, 0x5e3c177d,
        0x9bcdc00f, 0x5f76171b, 0x153e57c1, 0x8f30b5fa, 0x04b83a99, 0x0fe83239,
        0x818fb86c, 0x0d890ce8, 0x3f6d9a77, 0x1115b6c5, 0x006fd033, 0x1950e08a,
        0xebb8fc85, 0xe8403846, 0x0b1b219f, 0x1f5e066d, 0x153dd21d, 0x0c0fd098,
        0x9350639a, 0x04298857, 0x0716d97d, 0x90af569f, 0x58b7af6a, 0x2d312749,
        0x0e029ef1, 0x0e550a17, 0x7cb56f74, 0xfb7dd6f5, 0x06b43102, 0x2d4a1ad6,
        0x5a96bc30, 0x07d0a74a, 0x19112640, 0x5eb86e11, 0x4cc50508, 0x0c47ef24,
        0x59c39dbc, 0x34938b56, 0x8a9532e2, 0x0cd8deea, 0x19998a11, 0x07961a99,
        0x7632287e, 0x4e87c099, 0xae940156, 0x0adf09b4, 0x0afa5abc, 0x5fff4c03,
        0x1b0ca46a, 0x0bac26cd, 0x56254d42, 0x8f45fe19, 0x0c5b9f1b, 0xb114018e,
        0x6d83d2cd, 0x9690d517, 0xce14273c, 0xb6498e5a, 0x9386d174, 0xdb8351f4,
        0x0f47d8de, 0x5cbef2ec, 0x6c952843, 0x08ea8b04, 0x659f855c, 0x14015b4b,
        0x0c0c9522, 0x0c8ef58f, 0x5d47a226, 0x07126640, 0x0a7330f7, 0x0b97b150,
        0x84003002, 0x0e8b58b7, 0x0b09d711, 0x0d83d2cd, 0x0a9111f8, 0x1e7e07c7,
        0xcc99e2f1, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000
    };

    @Override
    public byte[] apply(byte @NotNull [] block, byte @NotNull [] key) {
        requireNonNull(block, "block");
        requireNonNull(key, "key");

        if (block.length != 4) {
            throw new IllegalArgumentException("Block must be 4 bytes (32 bits)");
        }
        if (key.length != 4) {
            throw new IllegalArgumentException("Round key must be 4 bytes (32 bits)");
        }

        // Convert block to 32-bit word
        int data = ((block[0] & 0xff) << 24)
            | ((block[1] & 0xff) << 16)
            | ((block[2] & 0xff) << 8)
            | (block[3] & 0xff);

        // Convert key to 32-bit word
        int roundKey = ((key[0] & 0xff) << 24)
            | ((key[1] & 0xff) << 16)
            | ((key[2] & 0xff) << 8)
            | (key[3] & 0xff);

        // MARS E-function: data = (data + roundKey) <<< 13
        data = Integer.rotateLeft(data + roundKey, 13);

        // S-box lookup (using lower 9 bits)
        int sboxIndex = data & 0x1ff;
        if (sboxIndex >= S_BOX.length) {
            sboxIndex = sboxIndex % S_BOX.length;
        }
        int sboxValue = S_BOX[sboxIndex];

        // Mix: data = data ^ (data >>> 13) ^ (data << 8)
        data = data ^ (data >>> 13) ^ (data << 8);

        // Add S-box value
        data = data + sboxValue;

        // Final rotation: data = data <<< 13
        data = Integer.rotateLeft(data, 13);

        // Convert back to bytes
        return new byte[] {
            (byte) (data >>> 24),
            (byte) (data >>> 16),
            (byte) (data >>> 8),
            (byte) data
        };
    }
}

