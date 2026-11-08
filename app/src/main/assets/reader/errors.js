// A plain script, loaded first, so a module that fails to load is reported too. The capture phase
// is what sees a <script> element's own error, which never bubbles to the window. The fork's
// failures to open a book are asynchronous, so they arrive as unhandled rejections.
{
    const report = () => kamayoqBridge.postMessage(JSON.stringify({ type: 'failed', code: 'page-error' }))
    // Chromium raises this when a resize callback changes the layout again; nothing failed, and the
    // paginator's observers do it while a real book settles.
    const harmless = event => typeof event.message === 'string' && event.message.startsWith('ResizeObserver loop')
    addEventListener('error', event => { if (!harmless(event)) report() }, true)
    addEventListener('unhandledrejection', report)
}
