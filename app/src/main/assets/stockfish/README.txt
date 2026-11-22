ChessLens bundles four portable Stockfish UCI executables and two shared NNUE
evaluation networks from DroidFish 1.90 / F-Droid version code 100:

  lib/arm64-v8a/libstockfish.so   64-bit ARM
  lib/armeabi-v7a/libstockfish.so 32-bit ARM
  lib/x86/libstockfish.so         32-bit Intel
  lib/x86_64/libstockfish.so      64-bit Intel

The engines are packaged as native libraries so Android 10 and newer can
execute the matching binary from the read-only native library directory.
Android 10 blocks execution from writable app data. At install time Android
extracts only the ABI that matches the device; ChessLens runs the extracted
binary with the private NNUE directory as its working directory. The two NNUE
networks remain in assets and are copied to private storage on first use.

Pinned source commit:
https://github.com/peterosterlund2/droidfish/tree/06948d542f5b354624a8d88fc5d6f3019669e539

Corresponding Stockfish source and Android build files are included in the
ChessLens source tree at third_party/stockfish-droidfish-1.90/.
License: GPL-3.0-or-later. See Copying.txt in this directory.
