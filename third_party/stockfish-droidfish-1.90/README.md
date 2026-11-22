# Stockfish engine provenance

The baseline (non-dotprod) ARM64 executable and its two NNUE network files in the app are from the
F-Droid DroidFish 1.90 build (`org.petero.droidfish`, version code 100).
F-Droid's build recipe pins that build to DroidFish source commit
`06948d542f5b354624a8d88fc5d6f3019669e539` and Android NDK r21e:

- Source: <https://github.com/peterosterlund2/droidfish/tree/06948d542f5b354624a8d88fc5d6f3019669e539>
- F-Droid build recipe: <https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.petero.droidfish.yml>
- F-Droid package: <https://f-droid.org/repo/org.petero.droidfish_100.apk>
- Source engine directory: `DroidFishApp/src/main/cpp/stockfish`
- Engine build configuration: `DroidFishApp/src/main/cpp/stockfish/Android.mk` and `build_sf.mk`

The checked-in source files here are copied from that pinned engine source
directory. `build/` contains the DroidFish Android NDK build files. The
bundled ARM64 `stockfish_nosimd` executable SHA-256 is:

`ecbdca46edffcc75f916fce50b3a121820386974edb8ebb90fd5e25c1acd120b`

Stockfish is licensed under GPL-3.0-or-later. See `Copying.txt`; the
corresponding engine source and build files are kept alongside this notice.
The engine is a separate UCI executable, not linked into ChessLens.
