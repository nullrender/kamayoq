// Kavita's bookScrollId (step 4, milestone C2): a path of tag[n] steps from a section's body, the
// form Kavita's web reader writes and reads (reader.service.ts at v0.9.1.4, getXPathTo and
// descopeBookReaderXpath). It is taken here on the document the reader rendered, never on one parsed
// apart: what the web reader counts is the section as shown. A chunk of a large section keeps every
// element of the section, empty and of the same tag, so its paths are the section's.

const MAX_LENGTH = 1024
const TAG = /^[a-z][a-z0-9-]*$/
const FORM = /^(?:\/\/body|id\("([^"\\]{1,256})"\))((?:\/[a-z][a-z0-9-]*\[[1-9][0-9]{0,5}\])*)$/
const STEP = /\/([a-z][a-z0-9-]*)\[([0-9]+)\]/g

// The section's own children: not the nodes the reader adds to a rendered document, which it marks
// cfi-inert because they are not in the book.
const own = el => Array.prototype.filter.call(el.children, child => !child.hasAttribute('cfi-inert'))

/** The marker of the element where [range] starts, or null when there is none to name. */
export const markerOf = range => {
    const container = range?.startContainer
    if (!container) return null
    let el = container.nodeType === 1 ? container.childNodes?.[range.startOffset] ?? container : container.parentElement
    // A page that starts in the blank between two blocks starts at the next one, not at their parent.
    if (container.nodeType === 3 && !container.textContent.trim() && container.nextElementSibling)
        el = container.nextElementSibling
    if (el && el.nodeType !== 1) el = el.parentElement
    const body = el?.ownerDocument?.body
    if (!body) return null
    let steps = []
    while (el && el !== body) {
        const parent = el.parentElement
        if (!parent) return null
        // A node the reader added, or a tag the form cannot write (a prefix, capitals): the place
        // becomes its parent, the nearest element both sides can name.
        if (el.hasAttribute('cfi-inert') || !TAG.test(el.localName)) {
            steps = []
        } else {
            const same = own(parent).filter(child => child.localName === el.localName)
            const index = same.indexOf(el)
            if (index < 0) steps = []
            else steps.unshift(`${el.localName}[${index + 1}]`)
        }
        el = parent
    }
    if (el !== body) return null
    const marker = ['//body', ...steps].join('/')
    // Kotlin accepts no longer marker (KavitaText.MAX_MARKER_LENGTH), and one this long could push the
    // whole message past its 8 KiB: the place goes without a marker instead.
    return marker.length <= MAX_LENGTH ? marker : null
}

/**
 * The element [marker] names in [doc], or null for a form this reader does not know, which opens
 * the section at its start. A path that stops short returns the deepest element it reached: a
 * chunk's outline holds only the section's top-level elements, and that is enough to find the chunk.
 */
export const elementOf = (doc, marker) => {
    const match = typeof marker === 'string' ? FORM.exec(marker) : null
    if (!match || !doc?.body) return null
    let el = match[1] != null ? doc.getElementById(match[1]) : doc.body
    if (!el) return null
    for (const [, tag, n] of match[2].matchAll(STEP)) {
        const next = own(el).filter(child => child.localName === tag)[Number(n) - 1]
        if (!next) return el
        el = next
    }
    return el
}
