ChessLens bundles four portable Stockfish UCI executables and two shared NNUE
evaluation networks from DroidFish 1.90 / F-Droid version code 100:

  arm64-v8a/stockfish   64-bit ARM
  armeabi-v7a/stockfish 32-bit ARM
  x86/stockfish         32-bit Intel
  x86_64/stockfish      64-bit Intel

At runtime ChessLens installs only the executable matching the device ABI.

Pinned source commit:
https://github.com/peterosterlund2/droidfish/tree/06948d542f5b354624a8d88fc5d6f3019669e539

Corresponding Stockfish source and Android build files are included in the
ChessLens source tree at third_party/stockfish-droidfish-1.90/.
License: GPL-3.0-or-later. See Copying.txt in this directory.
