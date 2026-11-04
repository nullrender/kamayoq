# Name and identifier (decided on 24/10/2026)

- **Display name:** Kamayoq. Pronounced ka-ma-YOK (stress on the second-to-last syllable; the final "q" is a guttural k; reading it as k is fine).
- **Origin:** Quechua *kamayuq / kamayoq*, "the one in charge, the one who has something in his care"; title of the Inca official who kept and read the khipus (*khipu kamayuq*). Cusco five-vowel spelling (with "o"), so that a reader who does not know Quechua gets the sound right; the official three-vowel spelling would be "kamayuq".
- **Meaning for the project:** the discreet librarian, keeper of a record that only the keeper can read. Do not say "imposing" or "strong" in app text; the tone is dry and reserved.
- **applicationId:** `app.kamayoq.android`. Prefix `app.kamayoq` = the domain `kamayoq.app` reversed; third segment per platform; `app.kamayoq.ios` and `app.kamayoq.extension.<source>` reserved for future extensions. Fallback recorded in the original decision: `org.kamayoq.android` (not usable; see "Checks" and the note).
- **Repository:** `github.com/nullrender/kamayoq`. **F-Droid:** no app named "kamayoq".
- **Domain:** `kamayoq.app`, registered on 24/10/2026, with auto-renewal turned on. `.app` requires HSTS (HTTPS only).
- **Pending in the original decision:** check INPI (pePI, stem "kamayoq", classes 9 and 42) and Google Play.
- **Main names discarded:** Pinakes, Girok, Cordel (apps with the same name); Khahara (rhymes with Carrara); Shunya, Sifr, Zephirum, Nulla, Follia (collision or bad meaning).

## Checks (24/10/2026)
- `kamayoq.app`: **not registered** before the purchase (RDAP, 404).
- `kamayoq.org`: **registered by a third party** (RDAP, 200): registered on 17/02/2012, expires on 17/02/2035, registrar IONOS SE, name servers in the United Kingdom; unrelated to the project. The fallback `org.kamayoq.android` would use someone else's domain; if `.app` fails, the fallback must be something else (see the note).
- `github.com/nullrender/kamayoq`: available (404); no public repository named "kamayoq" on GitHub (search: 0).
- F-Droid: "does not have any apps matching kamayoq".
- INPI and Google Play: not checked on this date (automated access probably blocked).

## Note on the identifier
The alternative considered earlier was `io.github.nullrender.<name>`, precisely so as not to depend on renewing a domain. The domain route was chosen. Mitigation: auto-renewal turned on (or buying several years at once). The Android identifier **does not require** owning the domain (it is a convention), but F-Droid recommends it and the convention assumes ownership: if the domain passes to someone else, an identifier collision is possible. Since `kamayoq.org` already belongs to a third party, the original fallback is not usable; a consistent alternative: `io.github.nullrender.kamayoq`.

## Immediate consequences
- The project folder changed from `reader-app` to `kamayoq` on 24/10/2026.
- Release signing key with CN = Kamayoq, kept outside the repository.
- App text: the placeholder "[nome]" became "Kamayoq" in the interface text and in the manifest.
