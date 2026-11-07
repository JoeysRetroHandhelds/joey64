# AMD FidelityFX CAS and FSR 1 (MIT)

Used by the Vulkan present's colour and sharpening pass (`shaders/post_*.comp`, Settings › Video › Sharpening).
Unchanged copies:

- `cas/` FidelityFX CAS v1.0 (2020): `ffx_a.h`, `ffx_cas.h`, `LICENSE.txt`, from GPUOpen-Effects/FidelityFX-CAS
  (as vendored by xenia, third_party/FidelityFX-CAS).
- `fsr1/` FidelityFX Super Resolution 1.0 (v1.20210629): `ffx_a.h`, `ffx_fsr1.h`, from GPUOpen-Effects/FidelityFX-FSR
  (as vendored by rpcs3 3rdparty/GPUOpen at b2e52d6 and vita3k, the same files). The MIT notice is in each header;
  `LICENSE.txt` repeats it.

The shaders are compiled to SPIR-V ahead of time: `devtools/build-shaders.sh` writes `shaders/post_spv.h`.
