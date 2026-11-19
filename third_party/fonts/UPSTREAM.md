# Fonts in the APK

Every font here comes unchanged from the Google Fonts repository, `google/fonts`, default branch `main` (checked through the GitHub API on 19/11/2026), commit `cc5661d43cd4f9aad5567033a8e15ca28dc35c86`, folder `ofl/<family>/`. Each family's `METADATA.pb` there says `license: "OFL"`, and its `OFL.txt` is the SIL Open Font License 1.1. The license of each family travels inside the APK, in `app/src/main/assets/licenses/fonts/`, as the OFL asks of every copy (condition 2).

The OFL allows bundling the fonts with any software, the GPL-3.0-or-later app included, as long as the fonts keep their own license and are not sold by themselves (conditions 1 and 2). IBM Plex Serif has the Reserved Font Name "Plex": it is shipped unmodified, so the name stays.

| Family | File in the repository | Place in the app | SHA-256 |
|---|---|---|---|
| Bricolage Grotesque | `ofl/bricolagegrotesque/BricolageGrotesque[opsz,wdth,wght].ttf` | `app/src/main/res/font/bricolage_grotesque.ttf` | `413e7357809ddd12fd80a96a8a396de0e401638d4acd3cb3e37532f0472ac682` |
| Figtree | `ofl/figtree/Figtree[wght].ttf` | `app/src/main/res/font/figtree.ttf` | `26ad3db9b31ff7dde67a91ff515d022d2f495cd506590699cf264f0bfe6fb714` |
