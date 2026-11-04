// What the page tells the app about where the reader is, run by Node in the CI.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { relocatedMessage } from '../main/assets/reader/messages.js'

const detail = fraction => ({ section: { current: 2, total: 3 }, cfi: 'epubcfi(/6/6!/4/2)', fraction })

test('dado uma posição no meio virada pela pessoa, então a mensagem leva seção, total, CFI, fração e motivo', () => {
    assert.deepEqual(relocatedMessage(detail(0.5), 'page'),
        { type: 'relocated', section: 2, sections: 3, cfi: 'epubcfi(/6/6!/4/2)', fraction: 0.5, reason: 'page' })
})

// Piece 14: the view's own event drops the paginator's reason, which tells the person from a re-layout.
test('dado um reajuste do paginator, então o motivo vai como veio, para o Kotlin decidir', () => {
    assert.equal(relocatedMessage(detail(0.5), 'anchor').reason, 'anchor')
})

// In scrolled flow the fork turns next and previous with no reason at all, and only those turns:
// left as null, the app refuses the person's own page turn.
test('dado uma virada sem motivo em Rolar, então ela vai como virada de página da pessoa', () => {
    assert.equal(relocatedMessage(detail(0.5), null).reason, 'page')
})

// The fork measures the end of the page, which passes 1 on the last page by float error or with an
// odd number of columns: the app would refuse the last position.
test('dado a última página com fração acima de 1, então a fração vai como 1', () => {
    assert.equal(relocatedMessage(detail(1.0000000000000002)).fraction, 1)
    assert.equal(relocatedMessage(detail(1.2)).fraction, 1)
})

test('dado uma fração abaixo de 0, então ela vai como 0', () => {
    assert.equal(relocatedMessage(detail(-1e-17)).fraction, 0)
})

test('dado uma fração que não é número, então ela vai como veio e o Kotlin recusa', () => {
    assert.equal(relocatedMessage(detail(undefined)).fraction, undefined)
    assert.ok(Number.isNaN(relocatedMessage(detail(NaN)).fraction))
})
