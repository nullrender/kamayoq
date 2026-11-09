import './foliate-js/view.js'
import { EPUB } from './foliate-js/epub.js'
import { neutralizeRefresh } from './sections.js'
import { relocatedMessage } from './messages.js'

const send = message => kamayoqBridge.postMessage(JSON.stringify(message))

const view = document.createElement('foliate-view')
document.body.append(view)

// One entry at a time, on demand: the Kotlin side reads the ZIP, so no ZIP library runs here.
const open = async token => {
    const address = name => `/book/${token}/e/${name.split('/').map(encodeURIComponent).join('/')}`
    const index = await (await fetch(`/book/${token}/index.json`)).json()
    const sizes = new Map(index.entries.map(entry => [entry.name, entry.size]))
    const load = async name => {
        if (!sizes.has(name)) return null
        const response = await fetch(address(name))
        if (!response.ok) throw new Error('Entry refused')
        return response
    }
    const book = await new EPUB({
        entries: index.entries.map(entry => ({ filename: entry.name })),
        loadText: async name => (await load(name))?.text() ?? null,
        loadBlob: async name => (await load(name))?.blob() ?? null,
        getSize: name => sizes.get(name) ?? 0,
    }).init()
    // The module for fixed layout was left out of the copy: say so instead of failing on its import.
    if (book.rendition?.layout === 'pre-paginated') return send({ type: 'failed', code: 'fixed-layout' })
    book.transformTarget.addEventListener('data', event => {
        const { type } = event.detail
        if (type === 'application/xhtml+xml' || type === 'text/html')
            event.detail.data = Promise.resolve(event.detail.data)
                .then(data => typeof data === 'string' ? neutralizeRefresh(data) : data)
    })
    // Cancelled always: left alone, the fork opens it with window.open, outside the book's sandbox.
    view.addEventListener('external-link', event => {
        event.preventDefault()
        send({ type: 'link', href: event.detail.href })
    })
    await view.open(book)
    // Before the book's own styles, without !important: these are defaults, not a replacement for
    // the edition's typography or colors. Applying them before init avoids moving a restored CFI.
    view.renderer.setStyles([`
        :where(html) { font-size: 18px; }
        :where(body) { line-height: 1.5; text-align: left; hyphens: none; }
        :where(p) { text-indent: 1.5em; }
    `, ''])
    for (const side of ['top', 'bottom', 'left', 'right'])
        view.renderer.setAttribute(`margin-${side}`, '24px')
    // The view handles the paginator's event first and keeps the result in lastLocation; the
    // paginator's event is the one that says why it moved.
    view.renderer.addEventListener('relocate', ({ detail }) => send(relocatedMessage(view.lastLocation, detail.reason)))
    // Before the restore: changing the flow of an open book lands on another place.
    view.renderer.setAttribute('flow', index.flow)
    await view.init({ lastLocation: index.position })
    send({ type: 'restored' })
}

globalThis.kamayoqReader = {
    next: () => view.next(),
    previous: () => view.prev(),
}

kamayoqBridge.onmessage = ({ data }) => open(data)

send({ type: 'ready' })
