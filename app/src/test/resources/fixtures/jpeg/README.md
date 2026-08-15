# JPEG decoder fixtures

One 20×13 source picture — three gradients, one per channel, so every pixel
varies along both axes — encoded five ways plus one file that must be *refused*.
All were written by **ImageMagick 7** (and `cjpeg` for the restart file), never
by this project, so `JpegDecode` is checked against real encoders. 20×13 is
deliberately ragged: it is not a multiple of 8 or 16 on either axis, so every
subsampling mode ends with partial MCUs on both edges.

| Fixture           | Covers                                                     |
|-------------------|------------------------------------------------------------|
| `grey.jpg`        | single-component greyscale passthrough                     |
| `yuv444.jpg`      | 3-component, no chroma subsampling (1×1)                   |
| `yuv422.jpg`      | horizontal-only chroma subsampling (2×1)                   |
| `yuv420.jpg`      | full chroma subsampling (2×2)                              |
| `restart.jpg`     | `DRI` + an `RSTn` after every MCU (2×2, written by `cjpeg`) |
| `progressive.jpg` | `SOF2` — out of scope, must decode to **null**             |

Each decodable fixture has a matching `*.rgb`: the same file decoded by
ImageMagick's libjpeg with **fancy upsampling off**
(`-define jpeg:fancy-upsampling=off`), dumped as raw 8-bit RGB. That switch
makes libjpeg replicate chroma samples the same way `JpegDecode` does, so the
only differences left are IDCT and colour-conversion rounding —
`JpegDecodeTest` allows ≤ 2 per channel.

To regenerate:

```sh
magick \( -size 13x20 gradient: -rotate 90 \) \
       \( -size 20x13 gradient: \) \
       \( -size 20x13 gradient:'#204080'-'#e0a040' -colorspace gray \) \
       -combine -colorspace sRGB src.png
magick src.png -colorspace Gray -quality 92 grey.jpg
magick src.png -sampling-factor 1x1 -quality 92 yuv444.jpg
magick src.png -sampling-factor 2x1 -quality 92 yuv422.jpg
magick src.png -sampling-factor 2x2 -quality 92 yuv420.jpg
magick src.png ppm:- | cjpeg -quality 90 -sample 2x2 -restart 1B > restart.jpg
magick src.png -interlace JPEG -quality 92 progressive.jpg
for f in grey yuv444 yuv422 yuv420 restart; do
  magick -define jpeg:fancy-upsampling=off $f.jpg -depth 8 rgb:$f.rgb
done
rm src.png
```
