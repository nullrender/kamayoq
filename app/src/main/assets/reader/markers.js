// Kavita's bookScrollId (step 4, milestone C2): a path of tag[n] steps from a section's body, the
// form Kavita's web reader writes and reads (reader.service.ts at v0.9.1.4, getXPathTo and
// descopeBookReaderXpath). It is taken here on the document the reader rendered, never on one parsed
// apart: what the web reader counts is the section as shown. A chunk of a large section keeps every
// element of the section, empty and of the same tag, so its paths are the section's.

const TAG = /^[a-z][a-z0-9-]*$/
const FORM = /^(?:\/\/body|id\("([^"\\]{1,256})"\))((?:\/[a-z][a-z0-9-]*\[[1-9][0-9]{0,5}\])*)$/
const STEP = /\/([a-z][a-z0-9-]*)\[([0-9]+)\]/g

// The section's own children: not the nodes the reader adds to a rendered document, which it marks
// cfi-inert because they are not in the book.
const own = el => Array.prototype.filter.call(el.children, child => !child.hasAttribute('cfi-inert'))

/** The marker of the element where [range] starts, or null when there is none to name. */
export const markerOf = range => null

export const elementOf = (doc, marker) => null
