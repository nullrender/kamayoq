import './foliate-js/view.js'
import { EPUB } from './foliate-js/epub.js'
import { neutralizeRefresh } from './sections.js'
import { relocatedMessage, contentsOf, navigationQueue } from './messages.js'
import { markerOf } from './markers.js'

const send = message => kamayoqBridge.postMessage(JSON.stringify(message))

const view = document.createElement('foliate-view')
document.body.append(view)
let contents = []
const navigate = navigationQueue()

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
    contents = contentsOf(book.toc)
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
    view.renderer.addEventListener('relocate', ({ detail }) =>
        send(relocatedMessage(view.lastLocation, detail.reason, markerOf(view.lastLocation?.range))))
    // Before the restore: changing the flow of an open book lands on another place.
    view.renderer.setAttribute('flow', index.flow)
    const start = startOf(index)
    // A marker is no target the view resolves: the paginator takes the section and a function that
    // finds the element in the document it renders (in a chunk, also in the outline of chunks).
    if (start?.anchor) {
        await view.renderer.goTo(start)
        view.history.pushState(start.index)
    } else await view.init({ lastLocation: start })
    send({ type: 'restored' })
}

// A CFI whose spine item is gone (another edition of the book) makes the fork open the book's start,
// or nothing at all: the saved section is the nearest place the person was. A CFI that finds its
// section but not its path inside it already opens at the section's start (toRange gives null).
const startOf = index => {
    const inBook = target => {
        const found = view.resolveNavigation(target)
        return found != null && found.index >= 0 && found.index < view.book.sections.length
    }
    if (index.position && inBook(index.position)) return index.position
    if (Number.isInteger(index.section) && inBook(index.section)) return index.section
    return null
}

// The paginator's atEnd, checked against its own units: on the emulator it said true at the end of
// the first of three chunks of a large section (run 44336477967), so no later unit may hold anything.
// A chunk known to be empty reads as linear 'no' once split, so it does not hold the end back.
const atLastPage = () => {
    const renderer = view.renderer
    if (!renderer.atEnd) return false
    return !renderer.sections.slice(renderer.primaryIndex + 1).some(unit => unit.linear !== 'no')
}

globalThis.kamayoqReader = {
    // Past the last page there is no page to turn to: the app shows its end block (piece 23).
    next: () => navigate(() => atLastPage() ? send({ type: 'end' }) : view.next()),
    previous: () => navigate(() => view.prev()),
    contents: () => contents.map(({ index, title, depth }) => ({ index, title, depth })),
}

// Native messages have no source window. Book frames cannot issue commands through this channel.
addEventListener('message', event => {
    if (event.source !== null || typeof event.data !== 'string') return
    let command
    try { command = JSON.parse(event.data) } catch { return }
    if (!command || command.type !== 'contents' || !Number.isInteger(command.index)) return
    const item = contents[command.index]
    if (item) navigate(() => view.goTo(item.href))
})

kamayoqBridge.onmessage = ({ data }) => open(data)

send({ type: 'ready' })
