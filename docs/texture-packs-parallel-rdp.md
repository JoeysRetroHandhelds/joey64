# Texture packs on parallel-RDP: research (2026-11-05)

Joey wants texture packs. Today only GLideN64 can load them. This is what it would take to load them in parallel-RDP, our main renderer. Research only: nothing is built.

## Why people say it can't be done

The libretro announcement for parallel-RDP upscaling lists "Texture resolution packs – Again, no." Its reason: the RDP "only sees TMEM uploads and tile descriptors which point to raw bytes", so there's no texture to swap. They also chose not to do it, because parallel-RDP aims to match the real hardware bit for bit.

No one has tried since. GitHub has no parallel-RDP pack fork or issue. nx-mobile added packs in October 2026, for GLideN64 only ("ParaLLEl-RDP on Vulkan has none").

## Why it can be done

GLideN64 is a high-level renderer, but it builds its pack key from RDP-level state:

- `_loadHiresTexture` (`GLideN64/src/Textures.cpp`) hashes RDRAM using `gDP.loadInfo[tmem]`. That holds the texture-image address, uls/ult, width, height, size, load type and dxt, all filled by `gDPLoadTile` and `gDPLoadBlock`.
- It also uses the tile descriptor (lrs/uls, masks, clamps, line, size, palette).
- And the palette from `gDPLoadTLUT` (`gDP.TexFilterPalette`).
- The key is `txfilter_checksum` (Rice CRC) and, optionally, the strong CRC.

parallel-RDP receives the same commands: `op_set_texture_image`, `op_load_tile` / `load_block` / `load_tlut` and `op_set_tile` (`rdp_device.cpp`). So it can compute the same keys and use existing GLideN64 packs (`.hts`/`.htc` caches and PNG folders) unchanged. The pack code, GLideNHQ, is GPLv2-or-later, so we can copy it.

Sampling has room for it too. When upscaled, parallel-RDP divides the texture-coordinate steps by the scale factor (`shaders/interpolation.h`). Each sample therefore carries a texture position with 5 fractional bits, 1/32 of a texel. That's enough to sample a replacement up to 8x larger, and parallel-RDP upscales up to 8x.

## Design

1. **Keys (CPU):** keep a GLideN64-style `loadInfo` per TMEM address and a palette copy. At each textured draw, compute the key for each tile used, cached per load. Look it up in the pack index.
2. **Storage (GPU):** decoded replacements go in a large buffer or image array, with an Auto memory limit like nx-mobile's. They load on demand and the least-recently used are dropped.
3. **Shader:** a pack-only shader variant (specialisation constant, so the default build doesn't change). If a draw's tile has a replacement, sample it at the 1/32-texel position, using the tile's own wrap, mirror and clamp. Otherwise the TMEM path runs as now. Replaced texels go into the combiner like normal texels.
4. **Native 1x still works.** Packs look best upscaled, but they also apply at 1x (new art, sampled at native size). Upscaling stays the user's choice.

## Hard cases

- **S2DEX backgrounds:** GLideN64 keys these on the whole background (`_loadHiresBackground`, `gSP.bgImage`), which only the high-level microcode knows. Low-level, the RSP splits a background into many strips, each loaded separately, so whole-background pack entries won't match. Strips could be stitched back together by address, but expect these to fail at first.
- **Copy mode** (2D sprites, text, fills): parallel-RDP repeats the same texel across the upscaled pixels (`interpolation.h:60`). The pack path has to work out each sub-pixel's texture position itself.
- **Large textures loaded in pieces,** LoadBlock with dxt (`ReverseDXT`), and the Mario Kart 64 odd-height hack all have to be copied from GLideN64 exactly.
- **Mipmaps:** packs replace the base level only, as in GLideN64.
- **Framebuffer textures:** these never match a pack, and nothing changes for them.
- **Performance:** hashing every load. A load is at most 4 KB, and only runs with packs on.
- **Two-cycle mode, two textures:** both tiles need lookups.

## Plan

- **A. Measure first (CPU only, no rendering change):**
  - Compute the keys in parallel-RDP and count, per frame, how many textured draws find an entry in a real pack. Packs: OoT and Majora's Mask (Djipi), Mario 64, Paper Mario.
  - In the same build, run GLideN64 on the same save states and compare which keys it finds.
  - This shows whether low-level keys match in practice, before any shader work.
- **B. Render:** storage, the shader variant, wrap/mirror/clamp, copy mode, two-cycle.
- **C. Backgrounds:** stitch the strips, or accept them as unsupported.
- **D. Memory limit, the Library/Settings switch, the pack folder,** and the uncapped bench with packs off (must equal today) and on.

Needs: a device, and the core-side work coordinated with the emulator chat (joey64-core). Nothing starts without Joey's go.

## Sources

- libretro, "paraLLEl N64 – Low-level RDP upscaling is finally here!", the "Won't be done" section
- `joey64-core/GLideN64/src/Textures.cpp` (`_loadHiresTexture`, `_loadHiresBackground`), `gDP.cpp` (`gDPLoadTile`, `gDPLoadBlock`, `gDPLoadTLUT`)
- `joey64-core/mupen64plus-video-paraLLEl/parallel-rdp/parallel-rdp/`: `rdp_device.cpp`, `rdp_renderer.cpp` (`load_tile`), `shaders/texture.h`, `shaders/interpolation.h`
- nx-redux/nx-mobile PR 45 (GLideN64 packs on Android, Auto memory limit)
- Prior art for low-level renderers: DuckStation texture replacement (hashes VRAM uploads with a separate palette hash; "page mode" fallback), PCSX2 (hashes textures as the GS texture cache loads them)
