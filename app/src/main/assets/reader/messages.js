// The fork measures the end of the page, which passes 1 on the last page (float error, or an odd
// number of columns read two at a time), and the app refuses a fraction outside 0 to 1. Anything
// that is not a number goes as it came, for the app to refuse.
const bounded = fraction => typeof fraction === 'number' && !Number.isNaN(fraction)
    ? Math.min(1, Math.max(0, fraction))
    : fraction

// The reason comes from the paginator's own event: the view's event leaves it out. In scrolled flow
// the fork gives no reason only when it turns next or previous, which the person asked for.
// The marker is Kavita's name of the element the page starts at (markers.js), or null.
export const relocatedMessage = (detail, reason, marker = null) => ({
    type: 'relocated',
    section: detail.section.current,
    sections: detail.section.total,
    cfi: detail.cfi,
    fraction: bounded(detail.fraction),
    reason: reason ?? 'page',
    marker: null,
})

// Keep hrefs inside the shell: native controls select only an item's numeric index.
export const contentsOf = toc => {
    const result = []
    let visited = 0
    const visit = (items, depth) => {
        if (!Array.isArray(items) || depth > 16) return
        for (const item of items) {
            // Count even destination-less headings: otherwise a huge empty tree bypasses the cap.
            if (++visited > 5000) return
            if (!item || typeof item !== 'object') continue
            if (typeof item.href === 'string' && item.href.length && item.href.length <= 2048) {
                result.push({
                    index: result.length,
                    title: String(item.label ?? '').slice(0, 512),
                    depth,
                    href: item.href,
                })
            }
            visit(item.subitems, depth + 1)
        }
    }
    visit(toc, 0)
    return result
}

// A relocate arrives before the paginator releases its lock. Await its navigation promise instead
// of using the position event as a signal that another turn is safe.
export const navigationQueue = () => {
    let pending = Promise.resolve()
    return move => {
        const task = pending.then(move)
        // Preserve the caller's rejection, but do not leave every subsequent command blocked by it.
        pending = task.catch(() => {})
        return task
    }
}
