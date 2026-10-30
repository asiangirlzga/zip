"""
KBV - a small lossless video codec (prototype).

Idea (same as the 'X = 10, write XXXXX' pattern idea, applied to video):
  1. Frame 0 is stored as-is.
  2. Every later frame is stored as the DIFFERENCE from the previous frame.
     Pixels that did not change become 0, so static areas turn into long runs of
     zeros - a repeating pattern.
  3. All the difference data is then packed with LZMA (XZ), which collapses
     those runs of zeros to almost nothing.

Decoding reverses it exactly, so the video comes back bit-for-bit identical.

Container:  "KBV1" | width u16 | height u16 | channels u8 | frames u32 | lzma(payload)
"""
import lzma
import struct
import numpy as np

MAGIC = b"KBV1"


def encode(frames: np.ndarray) -> bytes:
    """frames: uint8 array of shape (n, h, w, c)."""
    n, h, w, c = frames.shape
    deltas = np.empty_like(frames)
    deltas[0] = frames[0]
    # difference modulo 256 (wraps, so it is exactly reversible)
    deltas[1:] = frames[1:] - frames[:-1]
    # bytes of each frame stored one after another
    payload = deltas.tobytes()
    packed = lzma.compress(
        payload,
        format=lzma.FORMAT_RAW,
        filters=[{"id": lzma.FILTER_LZMA2, "preset": 9 | lzma.PRESET_EXTREME,
                  "dict_size": 64 * 1024 * 1024}],
    )
    return MAGIC + struct.pack("<HHBI", w, h, c, n) + packed


def decode(blob: bytes) -> np.ndarray:
    assert blob[:4] == MAGIC, "not a KBV file"
    w, h, c, n = struct.unpack("<HHBI", blob[4:13])
    payload = lzma.decompress(
        blob[13:],
        format=lzma.FORMAT_RAW,
        filters=[{"id": lzma.FILTER_LZMA2, "preset": 9 | lzma.PRESET_EXTREME,
                  "dict_size": 64 * 1024 * 1024}],
    )
    deltas = np.frombuffer(payload, dtype=np.uint8).reshape(n, h, w, c)
    frames = np.cumsum(deltas, axis=0, dtype=np.uint8)  # wraps mod 256
    return frames
