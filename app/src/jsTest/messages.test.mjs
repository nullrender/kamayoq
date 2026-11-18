// What the page tells the app about where the reader is, run by Node in the CI.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chapterFraction, chapterOf, contentsOf, navigationQueue, orderedSender, relocatedMessage } from '../main/assets/reader/messages.js'

const detail = fraction => ({ section: { current: 2, total: 3 }, cfi: 'epubcfi(/6/6!/4/2)', fraction })

test('dado uma posição no meio virada pela pessoa, então a mensagem leva seção, total, CFI, fração e motivo', () => {
    assert.deepEqual(relocatedMessage(detail(0.5), 'page'),
        { type: 'relocated', section: 2, sections: 3, cfi: 'epubcfi(/6/6!/4/2)', fraction: 0.5, reason: 'page', marker: null })
})

// Milestone C2: the marker of the page's first element goes with the place, for Kavita.
test('dado o marcador do Kavita, então ele vai na mensagem com o lugar', () => {
    assert.equal(relocatedMessage(detail(0.5), 'page', '//body/p[3]').marker, '//body/p[3]')
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

// Milestone B7: reading crosses the chapters of a split file.
test('dado o item do sumário que o leitor diz, então o capítulo é o que o app deu a esse item', () => {
    const ofItem = [0, 0, 1, 2, 3, 3, 4, 4]
    assert.equal(chapterOf(ofItem, { id: 4 }), 3)
    assert.equal(chapterOf(ofItem, { id: 7 }), 4)
})

test('dado uma página antes do primeiro item, ou um item que o app não conhece, então é o primeiro capítulo', () => {
    assert.equal(chapterOf([2, 3], null), 0)
    assert.equal(chapterOf([2, 3], undefined), 0)
    assert.equal(chapterOf([2, 3], { id: 9 }), 0)
    assert.equal(chapterOf(undefined, { id: 0 }), 0)
})

test('dado a fração do livro no meio do capítulo, então a fração do capítulo é a parte dele já passada', () => {
    assert.equal(chapterFraction(0.5, 0.4, 0.8), 0.25)
    assert.equal(chapterFraction(0.8, 0.4, 0.8), 1)
})

test('dado uma página que começa antes ou acaba depois do capítulo, então a fração fica entre 0 e 1', () => {
    assert.equal(chapterFraction(0.39, 0.4, 0.8), 0)
    assert.equal(chapterFraction(0.81, 0.4, 0.8), 1)
})

test('dado um começo ou um fim de capítulo que a página não mede, então a fração é desconhecida, não zero', () => {
    assert.equal(chapterFraction(0.5, null, 0.8), null)
    assert.equal(chapterFraction(0.5, 0.4, undefined), null)
    assert.equal(chapterFraction(NaN, 0.4, 0.8), null)
    assert.equal(chapterFraction(0.5, 0.4, 0.4), null)
})

test('dado uma mensagem que demora a ficar pronta e outra imediata, então elas saem na ordem em que foram pedidas', async () => {
    const sent = []
    const post = orderedSender(message => sent.push(message))
    let finish
    const slow = new Promise(resolve => { finish = resolve })
    post(async () => { await slow; return 'relocated' })
    const last = post(() => 'end')
    await Promise.resolve()
    assert.deepEqual(sent, [])
    finish()
    await last
    assert.deepEqual(sent, ['relocated', 'end'])
})

test('dado uma mensagem que falha ao ser feita, então as seguintes ainda saem', async () => {
    const sent = []
    const post = orderedSender(message => sent.push(message))
    post(() => { throw new Error('quebrou') })
    await post(() => 'end')
    assert.deepEqual(sent, ['end'])
})
