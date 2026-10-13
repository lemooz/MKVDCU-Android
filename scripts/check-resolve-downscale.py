"""CPU oracle for the 128-lane resolve downscale kernel (not a GPU test).

Checks byte/halfword packing, 64-bit components, source offsets, asymmetric
scales and half-pixel selection against an independent per-pixel copy.
"""
import random
import struct


def reference(source, size, sx, sy, half, offset, tiles):
    sample = (sx // 2 + (sy // 2) * sx) if half and sx * sy > 1 else 0
    result = bytearray()
    for pixel in range(tiles * 1024):
        start = offset + (pixel * sx * sy + sample) * size
        result += source[start:start + size]
    return result


def word_kernel(source, size, sx, sy, half, offset, tiles):
    src = struct.unpack('<%dI' % (len(source) // 4), source)
    tile_words = 1024 * size // 4
    result = [None] * (tiles * tile_words)
    sample = (sx // 2 + (sy // 2) * sx) if half and sx * sy > 1 else 0
    for tile in range(tiles):
        base = offset + tile * 1024 * size * sx * sy + sample * size
        for lane in range(128):
            for word in range(lane, tile_words, 128):
                value = 0
                if size < 4:
                    for i in range(4 // size):
                        pos = base + (word * (4 // size) + i) * size * sx * sy
                        value |= ((src[pos // 4] >> ((pos & 3) * 8)) &
                                  ((1 << (size * 8)) - 1)) << (i * size * 8)
                else:
                    pos = base + (word // (size // 4)) * size * sx * sy
                    pos += (word % (size // 4)) * 4
                    value = src[pos // 4]
                index = tile * tile_words + word
                assert result[index] is None, 'overlapping writes'
                result[index] = value
    assert None not in result, 'unwritten words'
    return struct.pack('<%dI' % len(result), *result)


def main():
    random_source = random.Random(0x360)
    cases = 0
    for size in (1, 2, 4, 8):
        for sx in (1, 2, 3, 6):
            for sy in (1, 2, 3, 6):
                for half in (False, True):
                    for offset in (0, size, 17 * size):
                        tiles = 3
                        length = (offset + tiles * 1024 * size * sx * sy + 3) & ~3
                        source = random_source.randbytes(length)
                        assert word_kernel(source, size, sx, sy, half, offset, tiles) == \
                            reference(source, size, sx, sy, half, offset, tiles), \
                            (size, sx, sy, half, offset)
                        cases += 1
    print(f'PASS: {cases} cases; complete, non-overlapping writes; byte-exact CPU oracle')


if __name__ == '__main__':
    main()
