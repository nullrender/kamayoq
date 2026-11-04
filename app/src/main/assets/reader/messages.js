// The fork measures the end of the page, which passes 1 on the last page (float error, or an odd
// number of columns read two at a time), and the app refuses a fraction outside 0 to 1. Anything
// that is not a number goes as it came, for the app to refuse.
const bounded = fraction => typeof fraction === 'number' && !Number.isNaN(fraction)
    ? Math.min(1, Math.max(0, fraction))
    : fraction

// The reason comes from the paginator's own event: the view's event leaves it out. In scrolled flow
// the fork gives no reason only when it turns next or previous, which the person asked for.
export const relocatedMessage = (detail, reason) => ({
    type: 'relocated',
    section: detail.section.current,
    sections: detail.section.total,
    cfi: detail.cfi,
    fraction: bounded(detail.fraction),
    reason: reason ?? 'page',
})
