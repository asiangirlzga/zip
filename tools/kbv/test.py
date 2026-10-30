import lzma, os, subprocess, sys, tempfile, time
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import kbv

W, H, N = 640, 360, 90
rng = np.random.default_rng(1)


def screen_video():
    """Screen-recording style: static UI + small moving box + a blinking cursor."""
    base = np.zeros((H, W, 3), np.uint8)
    base[:] = (30, 30, 40)
    base[:40] = (60, 90, 200)                       # title bar
    for y in range(80, 330, 18):                    # fake text lines
        x = 30
        while x < W - 40:
            ln = int(rng.integers(20, 80))
            base[y:y + 8, x:x + ln] = (220, 220, 220)
            x += ln + 12
    out = np.repeat(base[None], N, 0).copy()
    for i in range(N):
        x = 40 + i * 5
        out[i, 200:240, x:x + 40] = (255, 80, 80)   # moving box
        if (i // 10) % 2 == 0:
            out[i, 300:320, 500:504] = (255, 255, 255)  # cursor
    return out


def camera_video():
    """Camera style: moving scene plus sensor noise on every frame."""
    yy, xx = np.mgrid[0:H, 0:W]
    out = np.empty((N, H, W, 3), np.uint8)
    for i in range(N):
        g = (127 + 100 * np.sin((xx + i * 4) / 40.0) * np.cos(yy / 50.0)).astype(np.int16)
        frame = np.stack([g, g * 0.8, g * 0.6], -1)
        frame += rng.integers(-6, 7, frame.shape)
        out[i] = np.clip(frame, 0, 255).astype(np.uint8)
    return out


def ffmpeg_size(frames, codec_args):
    n, h, w, _ = frames.shape
    with tempfile.NamedTemporaryFile(suffix=".mkv", delete=False) as f:
        path = f.name
    p = subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{w}x{h}", "-r", "30", "-i", "-", *codec_args, path],
        input=frames.tobytes(), capture_output=True)
    if p.returncode != 0:
        return None
    s = os.path.getsize(path)
    os.remove(path)
    return s


def run(name, frames):
    raw = frames.nbytes
    t = time.time()
    blob = kbv.encode(frames)
    enc = time.time() - t
    t = time.time()
    back = kbv.decode(blob)
    dec = time.time() - t
    ok = np.array_equal(back, frames)
    lz = len(lzma.compress(frames.tobytes(), preset=6))
    ffv1 = ffmpeg_size(frames, ["-c:v", "ffv1"])
    x264 = ffmpeg_size(frames, ["-c:v", "libx264rgb", "-qp", "0", "-preset", "veryslow"])
    print(f"\n== {name} ({N} frames {W}x{H}) ==")
    print(f"raw frames         : {raw/1e6:9.2f} MB")
    print(f"plain XZ on raw    : {lz/1e3:9.1f} KB")
    print(f"FFV1 lossless      : {'n/a' if ffv1 is None else f'{ffv1/1e3:9.1f} KB'}")
    print(f"x264 lossless      : {'n/a' if x264 is None else f'{x264/1e3:9.1f} KB'}")
    print(f"KBV (mine)         : {len(blob)/1e3:9.1f} KB   ({raw/len(blob):.0f}x smaller)")
    print(f"round-trip identical: {ok}   encode {enc:.1f}s  decode {dec:.2f}s")


run("Screen recording", screen_video())
run("Camera-like (noisy)", camera_video())
