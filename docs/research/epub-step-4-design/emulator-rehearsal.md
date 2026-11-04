# Emulator rehearsal (step 4, piece 1)

Measured on 02/11/2026 on GitHub Actions `ubuntu-latest` (ubuntu-24.04), with `ReactiveCircus/android-emulator-runner` and the test `WebViewRehearsalTest`. The test asks for the functions foliate-js needs (`Object.groupBy`, `Map.groupBy`) and for an answer from the page, not for a version number.

## Result

| Image | Try | Result | Job | Boot | Build | Emulator step | WebView |
|---|---|---|---|---|---|---|---|
| `default` 36 x86_64 | 1 | green | 6.1 min | 69 s | 203 s | 143 s | `com.android.webview` 133.0.6943.137 |
| `default` 36 x86_64 | 2 | green | 5.2 min | 51 s | 157 s | 134 s | `com.android.webview` 133.0.6943.137 |
| `google_apis` 34 x86_64 | 1 | red | 13.9 min | did not boot | 123 s | 688 s | not available |
| `google_apis` 34 x86_64 | 2 | red | 14.7 min | did not boot | 197 s | 666 s | not available |

- `/dev/kvm` exists on the runner; with the `udev` rule it opens for everyone (`crw-rw-rw-`).
- `google_apis` 34 stopped both times with "Timeout waiting for emulator to boot", at the action's default limit of 600 s. The cause was not investigated: the image was dropped, not diagnosed.
- Decision: `default` 36, no matrix. WebView 133 is above the floor of 117.

## Failure rate

Two runs give no rate, only "it has not failed so far". Later runs on this image, while the reader was built, failed twice on infrastructure (a corrupt emulator download; touches landing outside the app's window) and passed on the next try with no code change.

## Not measured

- A green emulator does not prove a real phone: a phone maker's WebView can differ.
- API 31, the `minSdk`, and a WebView below the floor: no real coverage.
