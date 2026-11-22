import './foliate-js/view.js'
import { EPUB } from './foliate-js/epub.js'
import { neutralizeRefresh } from './sections.js'
import { relocatedMessage, contentsOf, navigationQueue, chapterOf, chapterFraction, orderedSender } from './messages.js'
import { elementOf, markerOf } from './markers.js'
import { DEFAULT_LOOK, checkedLook, stylesOf, marginOf, markAligned, hostBackgroundOf } from './look.js'

const send = message => kamayoqBridge.postMessage(JSON.stringify(message))
// What tells where the reader is leaves in order: a place may wait for its chapter to be measured.
const post = orderedSender(send)

const view = document.createElement('foliate-view')
document.body.append(view)
let contents = []
const navigate = navigationQueue()

// The settings panel's look: in the book's index before it opens, by command while it is open.
const applyLook = look => {
    view.renderer.setStyles(stylesOf(look, location.origin))
    for (const side of ['left', 'right']) view.renderer.setAttribute(`margin-${side}`, marginOf(look))
    document.body.style.background = hostBackgroundOf(look)
}

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
    // Each section the book's own way first: alignment and indent leave its centered lines alone.
    view.renderer.addEventListener('load', ({ detail }) => markAligned(detail.doc))
    // Applied before init, so a restored CFI does not move. The defaults go under the book's own
    // styles; only what the person chose goes over them (look.js).
    for (const side of ['top', 'bottom'])
        view.renderer.setAttribute(`margin-${side}`, '24px')
    applyLook(checkedLook(index.look) ?? DEFAULT_LOOK)
    const inChapter = index.chapters ? chapterMeter(index.chapters) : null
    // The view handles the paginator's event first and keeps the result in lastLocation; the
    // paginator's event is the one that says why it moved. The marker is taken now, while the range
    // is the one on screen.
    view.renderer.addEventListener('relocate', ({ detail }) => {
        const location = view.lastLocation
        const message = relocatedMessage(location, detail.reason, markerOf(location?.range))
        post(async () => inChapter ? { ...message, ...await inChapter(location) } : message)
    })
    // Before the restore: changing the flow of an open book lands on another place.
    view.renderer.setAttribute('flow', index.flow)
    const start = startOf(index)
    // A marker, or a place with the chapter's start behind it, is no target the view resolves: the
    // paginator takes the section and a function that finds the place in the document it renders (in
    // a chunk, also in the outline of chunks). Only those targets are objects: a CFI is a string, and
    // every string has a legacy `anchor` method.
    if (typeof start === 'object' && start !== null) {
        await view.renderer.goTo(start)
        view.history.pushState(start.index)
    } else await view.init({ lastLocation: start })
    post(() => ({ type: 'restored' }))
}

// Milestone B7, for a file split by its table of contents: tells, for a place the fork reports, the
// chapter it is in and how far into that chapter the page goes. The chapters come from the app, as
// their destinations and the chapter each item of the contents leads to.
const chapterMeter = chapters => {
    const sectionStarts = view.getSectionFractions()
    const targets = chapters.keys.map((key, chapter) => ({ key, chapter, section: view.resolveNavigation(key)?.index }))
    const sizeOf = section => view.book.sections[section]?.size
    const measured = new Map()
    // One parse of a section measures every chapter that starts inside it, and the document goes. A
    // section too large to parse again leaves its chapters unmeasured.
    const measure = section => {
        if (!measured.has(section)) measured.set(section, (async () => {
            const inside = new Map()
            if (!(sizeOf(section) <= MEASURED_SECTION)) return inside
            const doc = await view.book.sections[section].createDocument()
            for (const { key } of targets.filter(target => target.section === section && target.key.includes('#'))) {
                const element = view.book.getTOCFragment(doc, key.slice(key.indexOf('#') + 1))
                if (element) inside.set(key, textBefore(doc, element))
            }
            return inside
        })().catch(() => new Map()))
        return measured.get(section)
    }
    // The fraction of the book where a chapter starts: 1 past the last one, null when not known.
    const startOf = async chapter => {
        if (chapter >= targets.length) return 1
        const { key, section } = targets[chapter]
        const from = sectionStarts[section], to = sectionStarts[section + 1]
        if (!Number.isFinite(from) || !Number.isFinite(to)) return null
        if (!key.includes('#')) return from
        const inside = (await measure(section)).get(key)
        return inside === undefined ? null : from + inside * (to - from)
    }
    // The section's units in the paginator's list, one per piece, in order.
    const piecesOf = section => {
        const id = view.book.sections[section]?.id
        const units = view.renderer.sections
        const first = units.findIndex(unit => unit.id === id)
        let count = 0
        while (first >= 0 && units[first + count]?.id === id) count++
        return { first, count }
    }
    // The piece that holds each chapter's heading, read in the pieces' own markup: the paginator cuts
    // a section by the length of its markup, which a share of its text does not follow. A section
    // above MEASURED_SECTION is not read again for this.
    const headingPieces = new Map()
    const headingPieceOf = section => {
        if (!headingPieces.has(section)) headingPieces.set(section, (async () => {
            if (!(sizeOf(section) <= MEASURED_SECTION)) return null
            const { first, count } = piecesOf(section)
            const wanted = targets.filter(target => target.section === section && target.key.includes('#'))
            const found = new Map()
            for (let piece = 0; piece < count; piece++) {
                const html = await view.renderer.sections[first + piece].loadContent()
                if (!html) continue
                const doc = new DOMParser().parseFromString(html, 'text/html')
                for (const { key } of wanted)
                    if (!found.has(key) && view.book.getTOCFragment(doc, key.slice(key.indexOf('#') + 1))) found.set(key, piece)
            }
            return found
        })().catch(() => null))
        return headingPieces.get(section)
    }
    // A section the paginator shows in pieces: a piece holds only its own headings, and for a page
    // past the last of them the fork names the last item of the whole section (emulator, 18/11/2026).
    // There the chapter is the last one whose heading this piece shows on or before the page's end;
    // with none in the piece, the last one whose heading an earlier piece holds. Null when the pieces
    // were not read: the app then keeps the chapter it was in.
    const inPieces = async (location, section) => {
        const here = targets.filter(target => target.section === section)
        const range = location.range
        const doc = range?.startContainer?.getRootNode()
        let shown = null
        for (const { key, chapter } of here) {
            const element = doc && key.includes('#') ? view.book.getTOCFragment(doc, key.slice(key.indexOf('#') + 1)) : null
            if (!element) continue
            if (range.comparePoint(element, 0) > 0) break
            shown = chapter
        }
        if (shown !== null) return shown
        const pieces = await headingPieceOf(section)
        if (!pieces) return null
        // The piece of the page's own document: in Scroll the paginator's primary unit lags behind it.
        const unit = doc ? view.renderer.unitIndexOf(doc) : -1
        if (unit < 0) return null
        const piece = unit - piecesOf(section).first
        let started = null
        for (const { key, chapter } of here) {
            // A chapter at the section's own start begins before every piece; one whose heading no
            // piece holds cannot be placed, and is passed over.
            const at = key.includes('#') ? pieces.get(key) : -1
            if (at !== undefined && at < piece) started = chapter
        }
        // Before the first chapter that starts in this section: still in the one before it.
        return started ?? Math.max(here[0].chapter - 1, 0)
    }
    return async location => {
        const section = location?.section?.current
        const pieces = sizeOf(section) > PIECES_ABOVE && targets.some(target => target.section === section)
        const chapter = pieces ? await inPieces(location, section) : chapterOf(chapters.ofItem, location?.tocItem)
        if (chapter === null) return {}
        const [start, end] = await Promise.all([startOf(chapter), startOf(chapter + 1)])
        return { chapter, chapterFraction: chapterFraction(location?.fraction, start, end) }
    }
}

// The paginator shows a section above this size in pieces (CHUNK_THRESHOLD in paginator.js).
const PIECES_ABOVE = 512 * 1024
// Above this a section is not parsed a second time to place its chapters: the fraction of a chapter
// inside it stays unknown, and so does the chapter of a page whose piece shows no heading.
const MEASURED_SECTION = 8 * 1024 * 1024

// How much of a document's text comes before an element, from 0 to 1.
const textBefore = (doc, element) => {
    const walker = doc.createTreeWalker(doc.body ?? doc.documentElement, NodeFilter.SHOW_TEXT)
    let before = 0, total = 0
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const length = node.nodeValue.length
        if (element.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING) before += length
        total += length
    }
    return total > 0 ? before / total : 0
}

// A CFI whose spine item is gone (another edition of the book) makes the fork open the book's start,
// or nothing at all: the saved section is the nearest place the person was. A CFI that finds its
// section but not its path inside it opens at the section's start (toRange gives null), which in a
// split file may be an earlier chapter: there the chapter's own start in that section comes first.
const startOf = index => {
    // Kavita counts the sections of a place from it: another count is another layout of the file, and
    // no section here is the same there (milestone C2).
    if (Number.isInteger(index.sections) && index.sections !== view.book.sections.length) return null
    const inBook = target => {
        const found = view.resolveNavigation(target)
        return found != null && found.index >= 0 && found.index < view.book.sections.length
    }
    // An unknown marker opens the section's start, never fails.
    if (typeof index.marker === 'string' && Number.isInteger(index.section) && inBook(index.section))
        return { index: index.section, anchor: doc => elementOf(doc, index.marker) ?? 0 }
    if (index.position && inBook(index.position)) {
        const place = view.resolveNavigation(index.position)
        const own = typeof index.start === 'string' && inBook(index.start) ? view.resolveNavigation(index.start) : null
        if (own?.index !== place.index || typeof place.anchor !== 'function' || typeof own.anchor !== 'function') return index.position
        return { index: place.index, anchor: doc => anchorIn(doc, place.anchor) ?? anchorIn(doc, own.anchor) ?? 0 }
    }
    // A chapter of a split file with no place yet, or whose place is gone: its own start (milestone B7).
    if (typeof index.start === 'string' && inBook(index.start)) return index.start
    if (Number.isInteger(index.section) && inBook(index.section)) return index.section
    return null
}

// What an anchor finds in a document, or null when it finds nothing or fails.
const anchorIn = (doc, anchor) => {
    try { return anchor(doc) ?? null } catch { return null }
}

// The paginator's atEnd, checked against its own units: on the emulator it said true at the end of
// the first of three chunks of a large section (run 44336477967), so no later unit may hold anything.
// A chunk known to be empty reads as linear 'no' once split, so it does not hold the end back. The
// units are counted from the page's own, not from its section's number, which counts no chunks.
const atLastPage = () => {
    const renderer = view.renderer
    if (!renderer.atEnd) return false
    return !renderer.sections.slice(renderer.primaryUnitIndex + 1).some(unit => unit.linear !== 'no')
}

globalThis.kamayoqReader = {
    // Past the last page there is no page to turn to: the app shows its end block (piece 23).
    next: () => navigate(() => atLastPage() ? post(() => ({ type: 'end' })) : view.next()),
    previous: () => navigate(() => view.prev()),
    contents: () => contents.map(({ index, title, depth }) => ({ index, title, depth })),
}

// Native messages have no source window. Book frames cannot issue commands through this channel.
addEventListener('message', event => {
    if (event.source !== null || typeof event.data !== 'string') return
    let command
    try { command = JSON.parse(event.data) } catch { return }
    if (command?.type === 'look') {
        const look = checkedLook(command.look)
        if (look && view.renderer) applyLook(look)
        return
    }
    if (!command || command.type !== 'contents' || !Number.isInteger(command.index)) return
    const item = contents[command.index]
    if (item) navigate(() => view.goTo(item.href))
})

kamayoqBridge.onmessage = ({ data }) => open(data)

send({ type: 'ready' })
