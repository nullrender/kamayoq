// The page's look from the settings panel (slice 5). Not applied yet: the fixed defaults stay.

/** The look as it was before slice 5, for a page opened with none. */
export const DEFAULT_LOOK = Object.freeze({
    font: 'literata', size: 18, lineHeight: 15, paragraphSpacing: 0, margin: 24, indent: true, justify: false,
    background: null, text: null, chosen: [],
})

/** The look with every value checked, or null when any of them is not one the app sends. */
export const checkedLook = look => (look && typeof look === 'object' ? look : null)

/** The two style sheets the paginator takes: [before the book's styles, after them]. */
export const stylesOf = (look, origin) => [`
    :where(html) { font-size: 18px; }
    :where(body) { line-height: 1.5; text-align: left; hyphens: none; }
    :where(p) { text-indent: 1.5em; }
`, '']

/** The side margins, which the paginator takes as attributes. */
export const marginOf = look => '24px'

/** Marks the paragraphs that the book's own styles center or set to the right. */
export const markAligned = doc => {}

/** The color behind the book's frames, the theme's background; empty keeps the page's own. */
export const hostBackgroundOf = look => ''
