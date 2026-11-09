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
import { contentsOf } from '../main/assets/reader/messages.js'
import { navigationQueue } from '../main/assets/reader/messages.js'

test('dado dois comandos rápidos então o segundo espera a virada anterior terminar', async () => {
    const navigate = navigationQueue()
    const events = []
    let finish
    const gate = new Promise(resolve => { finish = resolve })
    const first = navigate(async () => { events.push('next'); await gate; events.push('done') })
    const second = navigate(() => { events.push('previous') })
    await Promise.resolve()
    assert.deepEqual(events, ['next'])
    finish()
    await Promise.all([first, second])
    assert.deepEqual(events, ['next', 'done', 'previous'])
})

test('dado uma navegação que falhou então a falha chega ao chamador e a próxima ainda executa', async () => {
    const navigate = navigationQueue()
    await assert.rejects(async () => navigate(async () => { throw new Error('Failed turn') }), /Failed turn/)
    assert.equal(await navigate(() => 'next'), 'next')
})

test('dado um sumário com capítulos aninhados então a lista mantém ordem níveis e destinos', () => {
    assert.deepEqual(contentsOf([
        { label: 'Volume 1', href: 'v.xhtml', subitems: [{ label: 'Capítulo 1', href: 'v.xhtml#c1' }] },
        { label: 'Volume 2', href: 'v2.xhtml' },
    ]), [
        { index: 0, title: 'Volume 1', depth: 0, href: 'v.xhtml' },
        { index: 1, title: 'Capítulo 1', depth: 1, href: 'v.xhtml#c1' },
        { index: 2, title: 'Volume 2', depth: 0, href: 'v2.xhtml' },
    ])
})

test('dado um título sem destino então seus capítulos continuam navegáveis', () => {
    assert.deepEqual(contentsOf([{ label: 'Parte', subitems: [{ label: 'Capítulo', href: 'a.xhtml' }] }]),
        [{ index: 0, title: 'Capítulo', depth: 1, href: 'a.xhtml' }])
    assert.deepEqual(contentsOf(null), [])
})

test('dado um sumário enorme ou profundo então o trabalho fica limitado', () => {
    assert.equal(contentsOf(Array.from({ length: 5001 }, () => ({ label: 'x', href: 'a.xhtml' }))).length, 5000)
    let toc = [{ label: 'x', href: 'a.xhtml' }]
    for (let i = 0; i < 40; i++) toc = [{ label: 'x', href: 'a.xhtml', subitems: toc }]
    assert.equal(contentsOf(toc).length, 17)
})
