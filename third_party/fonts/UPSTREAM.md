# Fonts in the APK

Every font here comes unchanged from the Google Fonts repository, `google/fonts`, default branch `main` (checked through the GitHub API on 19/11/2026), commit `cc5661d43cd4f9aad5567033a8e15ca28dc35c86`, folder `ofl/<family>/`. Each family's `METADATA.pb` there says `license: "OFL"`, and its `OFL.txt` is the SIL Open Font License 1.1. The license of each family travels inside the APK, in `app/src/main/assets/licenses/fonts/`, as the OFL asks of every copy (condition 2).

The OFL allows bundling the fonts with any software, the GPL-3.0-or-later app included, as long as the fonts keep their own license and are not sold by themselves (conditions 1 and 2). IBM Plex Serif has the Reserved Font Name "Plex": it is shipped unmodified, so the name stays.

The reading letters sit under `reader/fonts/`, beside the reader's page, but the page serves only the files listed in `ReaderPage.kt`; they join that list with the reading panel.

| Family | File in the repository | Place in the app | SHA-256 |
|---|---|---|---|
| Bricolage Grotesque | `ofl/bricolagegrotesque/BricolageGrotesque[opsz,wdth,wght].ttf` | `app/src/main/res/font/bricolage_grotesque.ttf` | `413e7357809ddd12fd80a96a8a396de0e401638d4acd3cb3e37532f0472ac682` |
| Figtree | `ofl/figtree/Figtree[wght].ttf` | `app/src/main/res/font/figtree.ttf` | `26ad3db9b31ff7dde67a91ff515d022d2f495cd506590699cf264f0bfe6fb714` |
| Literata | `ofl/literata/Literata[opsz,wght].ttf` | `app/src/main/assets/reader/fonts/Literata.ttf` | `b41138c9373112f32abb589cc22e8674b06ed4048b0c513be922bdd26f274440` |
| Literata | `ofl/literata/Literata-Italic[opsz,wght].ttf` | `app/src/main/assets/reader/fonts/Literata-Italic.ttf` | `d483dfaeba9cbf4ce71d32a52ee65df82f7e35b15fff8d1011cdb242d1fcd465` |
| EB Garamond | `ofl/ebgaramond/EBGaramond[wght].ttf` | `app/src/main/assets/reader/fonts/EBGaramond.ttf` | `ef9512f92f6d579e5dc75af59a5a4b1b8b47d2eda89e00b954d44520e5369027` |
| EB Garamond | `ofl/ebgaramond/EBGaramond-Italic[wght].ttf` | `app/src/main/assets/reader/fonts/EBGaramond-Italic.ttf` | `bba2c4499c93c9612b90b9825d32b07da52fce2fe57562a1eb6b833553f93c4e` |
| IBM Plex Serif | `ofl/ibmplexserif/IBMPlexSerif-Regular.ttf` | `app/src/main/assets/reader/fonts/IBMPlexSerif-Regular.ttf` | `e882efa9c41949a528ac2369079ec5ef050c1c996bbd0bacce3c3326d44cf80d` |
| IBM Plex Serif | `ofl/ibmplexserif/IBMPlexSerif-Italic.ttf` | `app/src/main/assets/reader/fonts/IBMPlexSerif-Italic.ttf` | `4b75b38be4673527231f49c48818d090c913d5042dd5c747b525bf6185d29ecb` |
| IBM Plex Serif | `ofl/ibmplexserif/IBMPlexSerif-Bold.ttf` | `app/src/main/assets/reader/fonts/IBMPlexSerif-Bold.ttf` | `534c02c295999dd86e770457ece1d43db0de9256dd98bf741426f63ae904209e` |
| IBM Plex Serif | `ofl/ibmplexserif/IBMPlexSerif-BoldItalic.ttf` | `app/src/main/assets/reader/fonts/IBMPlexSerif-BoldItalic.ttf` | `2a32b76ac19c1942bf5942dbbd2a1566e5f1ae9833e421ebcf36d3522715e153` |
| Atkinson Hyperlegible | `ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf` | `app/src/main/assets/reader/fonts/AtkinsonHyperlegible-Regular.ttf` | `7fb917c89019896d0b52ee84b7cbb3304c18cb90b19a62f5e32712bd23e97669` |
| Atkinson Hyperlegible | `ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Italic.ttf` | `app/src/main/assets/reader/fonts/AtkinsonHyperlegible-Italic.ttf` | `021beda4d3c6edfc78872e436d74009f9a1bcb294331908fe5747c61d3dcc514` |
| Atkinson Hyperlegible | `ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Bold.ttf` | `app/src/main/assets/reader/fonts/AtkinsonHyperlegible-Bold.ttf` | `5a3b0c8cc8ca545155150b4512a1fa248298df121c50d6557e651e61fbdab92f` |
| Atkinson Hyperlegible | `ofl/atkinsonhyperlegible/AtkinsonHyperlegible-BoldItalic.ttf` | `app/src/main/assets/reader/fonts/AtkinsonHyperlegible-BoldItalic.ttf` | `7a46f1b0ef2f64b61065c1a6b5a11fcb9b019b44062d88ba121a083cc39e86bf` |
