// The page's look from the settings panel (slice 5). Defaults go under the book's own styles, in
// :where and without !important; a property the person chose wins over the book's CSS, and only that
// one (PERSONALITY, principle 7). Every value is checked here again, because it becomes CSS text.

const FAMILIES = {
    literata: 'Literata',
    garamond: 'EB Garamond',
    plex: 'IBM Plex Serif',
    atkinson: 'Atkinson Hyperlegible',
}

// The files in the APK (slice 1). Literata and EB Garamond are variable in weight.
const FACES = [
    ['Literata', 'Literata.ttf', 'normal', '200 900'],
    ['Literata', 'Literata-Italic.ttf', 'italic', '200 900'],
    ['EB Garamond', 'EBGaramond.ttf', 'normal', '400 800'],
    ['EB Garamond', 'EBGaramond-Italic.ttf', 'italic', '400 800'],
    ['IBM Plex Serif', 'IBMPlexSerif-Regular.ttf', 'normal', '400'],
    ['IBM Plex Serif', 'IBMPlexSerif-Bold.ttf', 'normal', '700'],
    ['IBM Plex Serif', 'IBMPlexSerif-Italic.ttf', 'italic', '400'],
    ['IBM Plex Serif', 'IBMPlexSerif-BoldItalic.ttf', 'italic', '700'],
    ['Atkinson Hyperlegible', 'AtkinsonHyperlegible-Regular.ttf', 'normal', '400'],
    ['Atkinson Hyperlegible', 'AtkinsonHyperlegible-Bold.ttf', 'normal', '700'],
    ['Atkinson Hyperlegible', 'AtkinsonHyperlegible-Italic.ttf', 'italic', '400'],
    ['Atkinson Hyperlegible', 'AtkinsonHyperlegible-BoldItalic.ttf', 'italic', '700'],
]

// The panel's limits (core TextNumber); line height and paragraph spacing in tenths.
const LIMITS = { size: [14, 26], lineHeight: [12, 20], paragraphSpacing: [0, 16], margin: [12, 44] }
const CHOSEN = new Set(['font', 'size', 'lineHeight', 'paragraphSpacing', 'margin', 'indent', 'justify', 'theme', 'footer'])
const COLOR = /^#[0-9A-Fa-f]{6}$/

/** The look as it was before slice 5, for a page opened with none. */
export const DEFAULT_LOOK = Object.freeze({
    font: 'literata', size: 18, lineHeight: 15, paragraphSpacing: 0, margin: 24, indent: true, justify: false,
    background: null, text: null, chosen: [],
})

/** The look with every value checked, or null when any of them is not one the app sends. */
export const checkedLook = look => {
    if (!look || typeof look !== 'object') return null
    if (!(look.font in FAMILIES)) return null
    for (const [name, [min, max]] of Object.entries(LIMITS))
        if (!Number.isInteger(look[name]) || look[name] < min || look[name] > max) return null
    if (typeof look.indent !== 'boolean' || typeof look.justify !== 'boolean') return null
    for (const name of ['background', 'text'])
        if (look[name] !== null && !(typeof look[name] === 'string' && COLOR.test(look[name]))) return null
    if (!Array.isArray(look.chosen) || !look.chosen.every(name => CHOSEN.has(name))) return null
    return {
        font: look.font, size: look.size, lineHeight: look.lineHeight, paragraphSpacing: look.paragraphSpacing,
        margin: look.margin, indent: look.indent, justify: look.justify,
        background: look.background, text: look.text, chosen: [...look.chosen],
    }
}

const tenths = value => (value / 10).toString()

const TAGS = ['p', 'li', 'dd', 'blockquote']
const anyText = tags => tags.map(tag => `body ${tag}`).join(', ')
// While the reader measures how the book aligns a paragraph, the person's alignment and indent are
// off; a paragraph the book centers or sets to the right (a scene break, a signature) keeps its own.
const plainText = tags => tags.map(tag => `html:not([data-kamayoq-measuring]) body ${tag}:not([data-kamayoq-align])`).join(', ')

/**
 * The two style sheets the paginator takes: [before the book's styles, after them]. [origin] is where
 * the reader's own files are, for the fonts.
 */
export const stylesOf = (look, origin) => {
    const family = name => `'${FAMILIES[name]}', serif`
    const faces = FACES.map(([name, file, style, weight]) =>
        `@font-face { font-family: '${name}'; src: url('${origin}/reader/fonts/${file}') format('truetype'); font-style: ${style}; font-weight: ${weight}; font-display: block; }`)
    const before = [
        ...faces,
        `:where(html) { font-size: ${look.size}px; }`,
        `:where(body) { font-family: ${family(look.font)}; line-height: ${tenths(look.lineHeight)}; text-align: left; hyphens: none;${look.text ? ` color: ${look.text};` : ''} }`,
        `:where(p) { text-indent: ${look.indent ? '1.5em' : '0'}; }`,
    ]
    const chosen = new Set(look.chosen)
    const after = []
    if (chosen.has('font'))
        after.push(`body, body *:not(code):not(pre):not(kbd):not(samp):not(tt) { font-family: ${family(look.font)} !important; }`)
    if (chosen.has('size'))
        after.push(`html { font-size: ${look.size}px !important; }`, `${anyText(TAGS)} { font-size: 1rem !important; }`)
    if (chosen.has('lineHeight'))
        after.push(`body, ${anyText(TAGS)} { line-height: ${tenths(look.lineHeight)} !important; }`)
    if (chosen.has('paragraphSpacing'))
        after.push(`body p { margin-top: 0 !important; margin-bottom: ${tenths(look.paragraphSpacing)}em !important; }`)
    if (chosen.has('indent'))
        after.push(`${plainText(['p'])} { text-indent: ${look.indent ? '1.5em' : '0'} !important; }`)
    if (chosen.has('justify'))
        after.push(`${plainText(TAGS)} { text-align: ${look.justify ? 'justify' : 'left'} !important; hyphens: ${look.justify ? 'auto' : 'manual'} !important; }`)
    // The page's theme is always the person's: the book's background would hide it.
    if (look.background) after.push(`html, body { background-color: ${look.background} !important; }`)
    return [before.join('\n'), after.join('\n')]
}

/** The side margins, which the paginator takes as attributes. */
export const marginOf = look => `${look.margin}px`

/**
 * Marks the paragraphs that [doc]'s own styles center or set to the right, read with the person's
 * choices off, so alignment and indent leave them as the edition has them.
 */
export const markAligned = doc => {
    const root = doc.documentElement
    root.setAttribute('data-kamayoq-measuring', '')
    try {
        for (const element of doc.querySelectorAll('p, li, dd, blockquote')) {
            const align = doc.defaultView.getComputedStyle(element).textAlign
            if (align === 'center' || align === 'right' || align === 'end' || align === '-webkit-center')
                element.setAttribute('data-kamayoq-align', '')
        }
    } finally {
        root.removeAttribute('data-kamayoq-measuring')
    }
}

/** The color behind the book's frames, the theme's background; empty keeps the page's own. */
export const hostBackgroundOf = look => look.background ?? ''

/**
 * Whether this WebView hyphenates Portuguese, or null when it cannot tell: a long word in a narrow box
 * breaks over more lines with hyphens on than off only where a dictionary exists.
 */
export const hyphenatesPortuguese = doc => {
    const body = doc.body
    if (!body) return null
    const probe = doc.createElement('div')
    probe.lang = 'pt'
    probe.style.cssText = 'position: absolute; visibility: hidden; left: 0; top: 0; width: 5em; font-size: 16px; line-height: 20px; overflow-wrap: normal; word-break: normal;'
    probe.textContent = 'paralelepípedo inconstitucionalmente'
    body.append(probe)
    try {
        probe.style.hyphens = 'manual'
        const plain = probe.getBoundingClientRect().height
        probe.style.hyphens = 'auto'
        return probe.getBoundingClientRect().height > plain
    } finally {
        probe.remove()
    }
}
