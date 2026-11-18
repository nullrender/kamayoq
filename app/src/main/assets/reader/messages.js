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
    marker,
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

// Milestone B7: in a file split by its table of contents, the chapter a page is in. The fork says
// which item of the contents the page is under, and the app says which chapter each item leads to.
// A page before the first item has none, and belongs to the first chapter.
export const chapterOf = (ofItem, item) => {
    const chapter = Array.isArray(ofItem) ? ofItem[item?.id] : undefined
    return Number.isInteger(chapter) ? chapter : 0
}

// How far into its chapter a page goes, from the book's fraction and the fractions where the chapter
// starts and ends; null when one of them is not known. The book's fraction is of the end of the page,
// so the last page of a chapter reaches 1.
export const chapterFraction = (fraction, start, end) =>
    [fraction, start, end].every(Number.isFinite) && end > start
        ? bounded((fraction - start) / (end - start))
        : null

// Messages leave in the order they were made, even when making one has to wait. One that fails to
// be made is left out and said in the console; the ones after it still leave.
export const orderedSender = send => {
    let last = Promise.resolve()
    return make => {
        last = last.then(make).then(send).catch(error => console.error(error))
        return last
    }
}
