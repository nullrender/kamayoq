// Slice 5: the chapter steps of a book and the place inside a chapter for Go to.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { neighbourOf, withinSpan } from '../main/assets/reader/moves.js'

const contents = [
    { index: 0, href: 's1.xhtml', depth: 0 },
    { index: 1, href: 's2.xhtml', depth: 0 },
    { index: 2, href: 's2.xhtml#parte', depth: 1 },
    { index: 3, href: 's3.xhtml', depth: 0 },
]

test('dado a página sob um item do sumário, então o próximo e o anterior são os vizinhos dele', () => {
    assert.equal(neighbourOf(contents, 's2.xhtml', 1), 2)
    assert.equal(neighbourOf(contents, 's2.xhtml', -1), 0)
})

test('dado o primeiro ou o último item, então não há para onde ir', () => {
    assert.equal(neighbourOf(contents, 's1.xhtml', -1), null)
    assert.equal(neighbourOf(contents, 's3.xhtml', 1), null)
})

test('dado a página sob nenhum item, então o próximo é o primeiro e não há anterior', () => {
    assert.equal(neighbourOf(contents, undefined, 1), 0)
    assert.equal(neighbourOf(contents, undefined, -1), null)
    assert.equal(neighbourOf([], undefined, 1), null)
})

test('dado um capítulo de 0,2 a 0,6 do livro, então metade dele fica em 0,4', () => {
    assert.ok(Math.abs(withinSpan(0.2, 0.6, 0.5) - 0.4) < 1e-9)
    assert.equal(withinSpan(0.2, 0.6, 0), 0.2)
    assert.equal(withinSpan(0.2, 0.6, 1), 0.6)
})

test('dado o começo ou o fim do capítulo desconhecido, então não há lugar', () => {
    assert.equal(withinSpan(null, 0.6, 0.5), null)
    assert.equal(withinSpan(0.2, undefined, 0.5), null)
})
