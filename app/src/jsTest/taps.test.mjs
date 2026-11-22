// Slice 5: which touches on the book reach the app as taps.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { tapFraction } from '../main/assets/reader/taps.js'

const touch = changes => ({ clientX: 300, frameLeft: 0, width: 400, onLink: false, selecting: false, ...changes })

test('dado um toque simples, então a fração é o lugar dele na largura da página', () => {
    assert.equal(tapFraction(touch({})), 0.75)
})

test('dado o quadro da seção deslocado, então a fração conta a partir da página e não do quadro', () => {
    // In Pages the frame of a section is wider than the screen and moves left page after page.
    assert.equal(tapFraction(touch({ clientX: 1300, frameLeft: -1200 })), 0.25)
})

test('dado um toque num link ou com texto selecionado, então não é toque para virar a página', () => {
    assert.equal(tapFraction(touch({ onLink: true })), null)
    assert.equal(tapFraction(touch({ selecting: true })), null)
})

test('dado um toque fora da página ou sem largura, então não há fração', () => {
    assert.equal(tapFraction(touch({ clientX: 500 })), null)
    assert.equal(tapFraction(touch({ clientX: -5 })), null)
    assert.equal(tapFraction(touch({ width: 0 })), null)
    assert.equal(tapFraction(touch({ clientX: Number.NaN })), null)
})
