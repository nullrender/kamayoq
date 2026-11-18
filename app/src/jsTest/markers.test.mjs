// Kavita's marker on the reader's side (step 4, milestone C2), run by Node in the CI. Node has no
// DOM: the elements here carry only what markers.js reads. The real rendered document is measured
// on the emulator (TextReaderKavitaTest).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { elementOf, markerOf } from '../main/assets/reader/markers.js'

// [tag, attributes, ...children]; text is a string child.
const build = spec => {
    const doc = { body: null, ids: new Map(), getElementById: id => doc.ids.get(id) ?? null }
    const make = (node, parent) => {
        if (typeof node === 'string') return { nodeType: 3, parentElement: parent, ownerDocument: doc }
        const [localName, attributes, ...rest] = node
        const el = { nodeType: 1, localName, parentElement: parent, ownerDocument: doc, attributes }
        el.hasAttribute = name => name in attributes
        el.childNodes = rest.map(child => make(child, el))
        el.children = el.childNodes.filter(child => child.nodeType === 1)
        if (attributes.id) doc.ids.set(attributes.id, el)
        return el
    }
    const html = make(spec, null)
    doc.body = html.children.find(child => child.localName === 'body')
    return doc
}

const book = () => build(['html', {}, ['head', {}], ['body', {},
    ['h1', {}, 'Capítulo'],
    ['div', { 'cfi-inert': '' }, ['p', {}, 'Do leitor']],
    ['p', {}, 'Um'],
    ['div', {}, ['p', {}, 'Dois'], ['p', { id: 'x' }, 'Três', ['em', {}, 'quatro']]],
    ['p', {}, 'Cinco'],
]])

const at = (el, startOffset = 0) => ({ startContainer: el, startOffset })
const text = el => el.childNodes.find(child => child.nodeType === 3)

test('dado o começo de um parágrafo, então o marcador é o caminho dele a partir do body', () => {
    const doc = book()
    assert.equal(markerOf(at(text(doc.body.children[4]))), '//body/p[2]')
})

// Kavita writes the reader's own nodes nowhere: counting them would name another paragraph there.
test('dado um nó que o leitor acrescentou entre irmãos, então ele não conta no número', () => {
    const doc = book()
    assert.equal(markerOf(at(text(doc.body.children[2]))), '//body/p[1]')
})

test('dado um elemento dentro de outro, então cada passo conta só os irmãos da mesma tag', () => {
    const doc = book()
    const em = doc.body.children[3].children[1].children[0]
    assert.equal(markerOf(at(em)), '//body/div[1]/p[2]/em[1]')
})

test('dado um lugar dentro do que o leitor acrescentou, então o marcador fica no pai', () => {
    const doc = book()
    assert.equal(markerOf(at(text(doc.body.children[1].children[0]))), '//body')
})

test('dado o próprio body com um deslocamento, então o marcador é o filho nesse lugar', () => {
    const doc = book()
    assert.equal(markerOf(at(doc.body, 2)), '//body/p[1]')
})

test('dado nada onde começar, então não há marcador', () => {
    assert.equal(markerOf(null), null)
    assert.equal(markerOf({ startContainer: null, startOffset: 0 }), null)
})

test('dado o marcador de um parágrafo, então o elemento achado é ele, e a volta dá o mesmo marcador', () => {
    const doc = book()
    for (const marker of ['//body/p[2]', '//body/div[1]/p[2]/em[1]', '//body/h1[1]'])
        assert.equal(markerOf(at(elementOf(doc, marker))), marker)
})

test('dado a forma id do leitor do Kavita, então acha o elemento e segue os passos', () => {
    const doc = book()
    assert.equal(elementOf(doc, 'id("x")'), doc.ids.get('x'))
    assert.equal(elementOf(doc, 'id("x")/em[1]'), doc.ids.get('x').children[0])
})

// The rule of milestone C2: an unknown form opens at the start of the section, it never fails.
test('dado idx:12 ou outra forma desconhecida, então não há elemento', () => {
    const doc = book()
    for (const marker of ['idx:12', '//div[1]/p[2]', '/body/p[1]', '//body/p[0]', '', null, 12, 'id("nada")'])
        assert.equal(elementOf(doc, marker), null, String(marker))
})

test('dado um caminho que para no meio, então volta o elemento mais fundo que achou', () => {
    const doc = book()
    assert.equal(elementOf(doc, '//body/div[1]/p[9]'), doc.body.children[3])
})
