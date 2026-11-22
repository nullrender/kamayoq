// Moves the bars and the Go to sheet ask for (slice 5).

/**
 * The item of [contents] [by] steps from the one whose destination is [href], for a book's previous
 * and next chapter; null at either end or when the page is under no item.
 */
export const neighbourOf = (contents, href, by) => {
    if (!Array.isArray(contents) || contents.length === 0) return null
    const at = typeof href === 'string' ? contents.findIndex(item => item.href === href) : -1
    // Before the first item, the next chapter is the first one.
    const to = at < 0 ? (by > 0 ? 0 : -1) : at + by
    return to >= 0 && to < contents.length ? to : null
}

/** The place in the book [value] of the way into a chapter that runs from [start] to [end]; null when either is not known. */
export const withinSpan = (start, end, value) => {
    if (!Number.isFinite(start) || !Number.isFinite(end) || !Number.isFinite(value)) return null
    return start + Math.min(1, Math.max(0, value)) * (end - start)
}
