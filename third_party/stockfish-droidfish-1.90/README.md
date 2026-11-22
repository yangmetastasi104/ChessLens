# Stockfish engine provenance

The four portable (`stockfish_nosimd`) executables and the two NNUE network files
in the app are from the F-Droid DroidFish 1.90 build (`org.petero.droidfish`,
version code 100). F-Droid's build recipe pins that build to DroidFish source
commit `06948d542f5b354624a8d88fc5d6f3019669e539` and Android NDK r21e:

- Source: <https://github.com/peterosterlund2/droidfish/tree/06948d542f5b354624a8d88fc5d6f3019669e539>
- F-Droid build recipe: <https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.petero.droidfish.yml>
- F-Droid package: <https://f-droid.org/repo/org.petero.droidfish_100.apk>
- Source engine directory: `DroidFishApp/src/main/cpp/stockfish`
- Engine build configuration: `DroidFishApp/src/main/cpp/stockfish/Android.mk` and `build_sf.mk`

The checked-in source files here are copied from that pinned engine source
directory. `build/` contains the DroidFish Android NDK build files. The
bundled executables were extracted from the F-Droid APK and verified against
their ZIP CRC-32 and uncompressed size:

| ABI | Asset path | Size (bytes) | SHA-256 |
| --- | --- | ---: | --- |
| `arm64-v8a` | `app/src/main/assets/stockfish/arm64-v8a/stockfish` | 674,208 | `ecbdca46edffcc75f916fce50a3b121820386974edb8ebb90fd5e25c1acd120b` |
| `armeabi-v7a` | `app/src/main/assets/stockfish/armeabi-v7a/stockfish` | 415,556 | `a0d4893214fb639e913232e2890aeb2dc82f7f727eb7563069efd175c54d2348` |
| `x86` | `app/src/main/assets/stockfish/x86/stockfish` | 759,628 | `033f4df73ce7ff5476e634d40c495ddea0cd0ef3b7de5339d6de17551f863840` |
| `x86_64` | `app/src/main/assets/stockfish/x86_64/stockfish` | 760,416 | `d0c20fa6478dc715c70b839e506db665381fedaf036ee565c717d8c4a492f402` |

The shared NNUE networks are:

| Asset path | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| `app/src/main/assets/stockfish/nn-b1a57edbea57.nnue` | 65,429,575 | `b1a57edbea574ca8b88d6837473845791beb53d885f87f86d5ccdd5659fbf3b2` |
| `app/src/main/assets/stockfish/nn-baff1ede1f90.nnue` | 3,480,122 | `baff1ede1f90c1dd1b4f772f1eff29848821801e8186345da7f0eb4121bd6f63` |

Stockfish is licensed under GPL-3.0-or-later. See `Copying.txt`; the
corresponding engine source and build files are kept alongside this notice.
The engine is a separate UCI executable, not linked into ChessLens.
