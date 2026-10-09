# SpeexDSP (vendored subset)

The acoustic echo canceller (`mdf.c`), preprocessor (`preprocess.c`) and their FFT/filterbank
support from [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1), unmodified.
`include/speex/speexdsp_config_types.h` replaces the configure-generated header.

Built in floating point with KISS FFT (`-DFLOATING_POINT -DUSE_KISS_FFT`) by `../Android.mk`.
Licensed under the BSD-style licence in `COPYING`.
