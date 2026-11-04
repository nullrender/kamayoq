# foliate-js: copy and record

A partial copy, with no build step, of Readest's fork of foliate-js. Why this fork: `docs/decisions/stack-2026-10-23.md`; design: `docs/research/epub-step-4-design.md`, piece 4.

- Repository: https://github.com/readest/foliate-js (MIT license)
- Default branch, by the API: `main`
- Commit: `0c7a0edcda672218e958fb92b4cdf090d7ca9daa`, of 26/10/2026
- Downloaded on 02/11/2026

Left out on purpose: `vendor/`, `pdf.js` and the other formats. `view.js` imports them only when asked, with `import()`. `fixed-layout.js` is left out too, so a fixed-layout EPUB needs a clear message (piece 13).

Updating is a deliberate act: in a clone of the fork, `git diff <old>..<new> -- <files>`; apply it with `git apply --directory=third_party/foliate-js`; run the emulator suite and redo the columns below. The test `third_party/upstream-check.test.mjs` turns red if a file changes without a record here.

## Files

| File | SHA-256 in the fork | SHA-256 here |
|---|---|---|
| `LICENSE` | `b621ffc351fe263bc9b09dd0bc1c9332fda9c462a3904c1e4f0b4d3236b9c28c` | `b621ffc351fe263bc9b09dd0bc1c9332fda9c462a3904c1e4f0b4d3236b9c28c` |
| `epub.js` | `5326553896410cc2f4d1dd21c7a824058afd2bf986fee8e584fd81339eadcb54` | `5326553896410cc2f4d1dd21c7a824058afd2bf986fee8e584fd81339eadcb54` |
| `epubcfi.js` | `89c0610cb6d3b482dde1552da2bc02e37ec804bc7b1916a1a86299e3c979d221` | `89c0610cb6d3b482dde1552da2bc02e37ec804bc7b1916a1a86299e3c979d221` |
| `overlayer.js` | `f0ef045950656ff6c0fefe01249d83215c58ba149bde528e26a6db531c349268` | `f0ef045950656ff6c0fefe01249d83215c58ba149bde528e26a6db531c349268` |
| `paginator.js` | `8556e845bbd9c88f45eea701b5b1fe282976ab41cfd812124e22bfd80a9710cd` | `21fda2a4e662a42d2383bfd0c41c504f23cc90f578c74e82efb810f5273613c7` |
| `progress.js` | `e9a6ba8b0b45802677de2ac7c6722299ab1d45df2cb780ea623ab2dc6d7cfd15` | `e9a6ba8b0b45802677de2ac7c6722299ab1d45df2cb780ea623ab2dc6d7cfd15` |
| `section-chunks.js` | `0d84803c201ce5b7d29f158f3bd7715291a01c697247aecd9fa6dde45e779f46` | `0d84803c201ce5b7d29f158f3bd7715291a01c697247aecd9fa6dde45e779f46` |
| `text-walker.js` | `a9ba231c192ee214ef664b2e01f712d199700bcf2be1c20a38c1b26024428aff` | `a9ba231c192ee214ef664b2e01f712d199700bcf2be1c20a38c1b26024428aff` |
| `view.js` | `c9ee7ce3a5e7bb6b3e52a29d5bec7ac2bd14bd117c935f9343edb53cdd4855f1` | `c9ee7ce3a5e7bb6b3e52a29d5bec7ac2bd14bd117c935f9343edb53cdd4855f1` |

## Changes

| File | What changed and why | Commit |
|---|---|---|
| `paginator.js` | Line 783: the book's frame loses `allow-scripts` and keeps only `allow-same-origin`, so no script of the book runs, not even one the CSP would let through (tests in `HostileBookSandboxTest`). The comment above the line, about the WebKit bug, stays as it came. | the commit "Take allow-scripts out of the book's frame" |
