# PNG decoder fixtures

Two encodings of the **same 13×9 RGBA image**, written by **ImageMagick 7** —
never by this project's own encoder — so `PngDecode`'s Adam7 support is checked
against a real PNG writer rather than against a twin of itself.

| Fixture                 | Covers                                                |
|-------------------------|-------------------------------------------------------|
| `adam7-plain.png`       | `IHDR.interlace_method = 0`, the ordinary top-to-bottom layout |
| `adam7-interlaced.png`  | `IHDR.interlace_method = 1`, the same pixels in seven Adam7 passes |

`RawImageCodecTest` decodes both and asserts they yield identical pixels, so a
wrong pass geometry cannot pass unnoticed. 13×9 is deliberately ragged: it is odd
on both axes, so every one of the seven passes exists and several end mid-step.

Every pixel is a function of its position — `r = 17x`, `g = 23y`,
`b = 7x + 13y`, `a = 255 − ((3x + 5y) mod 128)`, all mod 256 — so the value
varies along **both** axes and the test can re-derive the expected pixels from
the formula instead of trusting either file. To regenerate:

```sh
python3 - <<'PY'                       # the source pixels, as a 4-channel PAM
w, h = 13, 9
px = bytearray()
for y in range(h):
    for x in range(w):
        px += bytes([(x * 17) % 256, (y * 23) % 256,
                     (x * 7 + y * 13) % 256, 255 - (x * 3 + y * 5) % 128])
open('a7.pam', 'wb').write(
    f'P7\nWIDTH {w}\nHEIGHT {h}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n'
    .encode() + bytes(px))
PY
magick a7.pam -define png:color-type=6 -interlace None PNG32:adam7-plain.png
magick a7.pam -define png:color-type=6 -interlace PNG  PNG32:adam7-interlaced.png
magick adam7-plain.png txt:- | diff - <(magick adam7-interlaced.png txt:-)
```

The last line is the check that the pair really is the same picture, per a
decoder that is not ours.

Sub-byte interlacing has no fixture here: ImageMagick refuses to write a 1-bit
Adam7 PNG (`Cannot write image with defined png:bit-depth`), so that path is
covered by a hand-built image in `RawImageCodecTest` instead, alongside the other
bit-depth cases it already builds.
