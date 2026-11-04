// The reader's own text rules, run by Node in the CI before anything slow (piece 12).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { neutralizeRefresh } from '../main/assets/reader/sections.js'

test('dado meta refresh numa seção, então o atributo vira inerte e o elemento fica no lugar', () => {
    const before = '<head><title>x</title><meta http-equiv="refresh" content="0;url=PWNED"/></head>'
    assert.equal(neutralizeRefresh(before),
        '<head><title>x</title><meta data-kamayoq-http-equiv="refresh" content="0;url=PWNED"/></head>')
})

test('dado maiúsculas, aspas simples ou espaços, então o atributo também vira inerte', () => {
    assert.equal(neutralizeRefresh("<META content='0' HTTP-EQUIV = 'Refresh'>"),
        "<META content='0' data-kamayoq-http-equiv = 'Refresh'>")
})

test('dado http-equiv fora de meta, então o texto do livro não muda', () => {
    const text = '<p>O atributo http-equiv="refresh" aparece aqui como texto.</p><div http-equiv="x"></div>'
    assert.equal(neutralizeRefresh(text), text)
})

test('dado uma seção sem meta, então ela sai igual', () => {
    const text = '<html><head><meta charset="utf-8"/></head><body><p>Texto.</p></body></html>'
    assert.equal(neutralizeRefresh(text), text)
})
