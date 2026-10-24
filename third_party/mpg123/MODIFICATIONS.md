# mpg123: source and modifications

- Upstream: https://www.mpg123.de/ (release tarball `mpg123-1.33.7.tar.bz2`,
  SHA-256 `31d0e35a4ca567ec9b5ebda6c3062bb4435d6d3eacd6ef0d95cadd7854dc03ee`)
- Licence: GNU LGPL 2.1, see [COPYING](COPYING).
- Modifications: none. This directory is the unpacked release, apart from this file.

The app builds only `libmpg123.so` through the upstream CMake port (`ports/cmake`), with libout123,
the programs, network, ICY and ID3v2 support switched off (see `app/src/main/cpp/CMakeLists.txt`).
It is loaded dynamically and decodes DAB's MPEG-1/2 Audio Layer II, because Android's platform
decoders handle only Layer III.
