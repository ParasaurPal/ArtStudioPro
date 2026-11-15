# ArtStudio Pro — implementation status

This file records what is actually implemented in the current feature/full-art-studio work branch. A listed feature is not considered complete merely because its name appears in a menu.

## Implemented in this branch

- Android Compose studio screen with an Android raster canvas.
- Preset-based new documents for screen, print, postcard, and comic page sizes.
- Image import as a new editable raster layer, with oversized images downsampled before decoding.
- Native .asp project archives containing a JSON manifest and lossless PNG data for each layer.
- Project reopen restores document dimensions, layer order, visibility, opacity, alpha lock, blend mode, and transparent-page setting.
- Transparent-page checkerboard preview and transparent/white PNG export.
- Raster layer add, duplicate, delete, rename, reorder, clear, visibility, opacity, alpha lock, merge-down, and merge-visible operations.
- 27 named layer blend modes, using Android's blend pipeline on API 29+ and a reduced legacy fallback on older Android versions. Some advanced modes are approximations, not pixel-identical implementations.
- Bounded tile-based stroke undo/redo (128 px tiles, 20 stroke entries).
- Pressure-responsive stamp brush engine, stylus tilt/azimuth stamp shaping, and configurable starter brush presets.
- Eraser mode, horizontal/vertical symmetry, grid overlay, and high-zoom pixel grid.
- Pinch zoom and two-finger pan.
- Tolerance-based four-connected flood fill with undo snapshots.
- Whole-document horizontal/vertical flip and 180-degree rotation.
- Project archive size and decoded bitmap budgets to reduce malformed-file and memory-exhaustion risk.

## Not implemented yet

- Cross-platform iOS/desktop support. The current project is Android/Kotlin and is not a cross-platform codebase.
- Custom physical-unit canvas dimensions, DPI editing, crop/expand/resize, 90-degree rotation, and resampling controls.
- GPU-native OpenGL/Vulkan rendering pipeline, offscreen GPU layer compositing, 120 FPS guarantees, and latency instrumentation.
- Full stylus/finger mapping, pressure curve editor, velocity dynamics, palm rejection, custom gestures, keyboard mapping, and radial menus.
- Full textured brush engine, imported tips, grain, jitter, scatter, dual brushes, wet-media physics, impasto, brush folders, sharing, and QR brush exchange.
- Paper texture library, custom paper import, radial/kaleidoscope symmetry, rulers, Bezier guides, perspective grids, and shape snapping.
- Vector/text layers, groups, clipping masks, layer masks, reference layers, and sample-all-layers fill.
- Advanced fill modes, selections, lasso operations, transform/warp/mesh/liquify tools.
- Full 27-mode color-accurate blend behavior on every supported Android API level.
- RGB/CMYK/ICC color workflows, palette extraction/management, gradient and pattern editors.
- Filter/effect suite, comic panels, speech bubbles, screentones, typography tools, animation timeline, onion skinning, MP4/GIF export, and time-lapse recording.
- PSD import/export, JPEG/WEBP/TIFF/BMP/PDF export, batch export, printing, and native share-sheet integration.
- Automated tests and a verified APK from this branch.

## Build policy

The repository's current GitHub Actions workflow triggers on pushes to main and master. Work in feature/full-art-studio is kept off those trigger branches while builds are paused. Do not merge this branch or run a build until explicitly requested.
