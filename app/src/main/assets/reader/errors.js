// A plain script, loaded first, so a module that fails to load is reported too. The capture phase
// is what sees a <script> element's own error, which never bubbles to the window. The fork's
// failures to open a book are asynchronous, so they arrive as unhandled rejections.
{
    const report = () => kamayoqBridge.postMessage(JSON.stringify({ type: 'failed', code: 'page-error' }))
    addEventListener('error', report, true)
    addEventListener('unhandledrejection', report)
}
