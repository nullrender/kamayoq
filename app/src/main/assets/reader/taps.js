// A plain touch on the book becomes a tap for the app, which decides its zone (core TapZones).

/**
 * Where across the page a touch fell, from 0 to 1, or null when it is no plain touch: on a link, with
 * text selected, or with nothing to measure. [frameLeft] is the section's frame on the page.
 */
export const tapFraction = ({ clientX, frameLeft, width, onLink, selecting }) => {
    if (onLink || selecting) return null
    if (!(width > 0) || !Number.isFinite(clientX) || !Number.isFinite(frameLeft)) return null
    const x = (frameLeft + clientX) / width
    return x >= 0 && x <= 1 ? x : null
}
