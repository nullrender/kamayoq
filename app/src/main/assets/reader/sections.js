// A `<meta http-equiv>` can send a section's frame elsewhere. The CSP stops the trip but leaves the
// section blank, so the attribute is renamed instead: the element stays, and so do the positions
// (CFI) that count elements.
export const neutralizeRefresh = text =>
    text.replace(/(<meta\b[^>]*?\s)http-equiv(\s*=)/gi, '$1data-kamayoq-http-equiv$2')
