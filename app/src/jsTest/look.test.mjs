// The settings panel's look as CSS (slice 5): defaults under the book, what the person chose over it.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DEFAULT_LOOK, checkedLook, stylesOf, marginOf, hostBackgroundOf } from '../main/assets/reader/look.js'

const ORIGIN = 'https://appassets.androidplatform.net'
const look = changes => ({ ...DEFAULT_LOOK, background: '#EFE4CC', text: '#3A2E20', ...changes })

test('dado os padrões, então nada vai por cima do livro além do fundo do tema', () => {
    const [before, after] = stylesOf(look({}), ORIGIN)
    assert.match(before, /:where\(html\) \{ font-size: 18px; \}/)
    assert.match(before, /:where\(body\) \{ font-family: 'Literata', serif; line-height: 1\.5; text-align: left; hyphens: none; color: #3A2E20; \}/)
    assert.doesNotMatch(before, /!important/)
    assert.equal(after, 'html, body { background-color: #EFE4CC !important; }')
})

test('dado as letras, então cada arquivo vem da pasta de letras do leitor', () => {
    const [before] = stylesOf(look({}), ORIGIN)
    assert.match(before, /font-family: 'EB Garamond'; src: url\('https:\/\/appassets\.androidplatform\.net\/reader\/fonts\/EBGaramond\.ttf'\)/)
    assert.equal(before.match(/@font-face/g).length, 12)
})

test('dado o tamanho escolhido, então ele vence o tamanho do livro no texto corrido', () => {
    const [before, after] = stylesOf(look({ size: 22, chosen: ['size'] }), ORIGIN)
    assert.match(before, /:where\(html\) \{ font-size: 22px; \}/)
    assert.match(after, /html \{ font-size: 22px !important; \}/)
    assert.match(after, /body p, body li, body dd, body blockquote \{ font-size: 1rem !important; \}/)
})

test('dado a letra escolhida, então ela vence a do livro, menos no código', () => {
    const [, after] = stylesOf(look({ font: 'garamond', chosen: ['font'] }), ORIGIN)
    assert.match(after, /body, body \*:not\(code\):not\(pre\):not\(kbd\):not\(samp\):not\(tt\) \{ font-family: 'EB Garamond', serif !important; \}/)
})

test('dado a letra trocada sem ser escolhida, então ela fica só por baixo do livro', () => {
    const [before, after] = stylesOf(look({ font: 'plex' }), ORIGIN)
    assert.match(before, /:where\(body\) \{ font-family: 'IBM Plex Serif', serif;/)
    assert.doesNotMatch(after, /font-family/)
})

test('dado Justificar e hifenizar, então o texto justifica e hifeniza, menos o que o livro centra', () => {
    const [, after] = stylesOf(look({ justify: true, chosen: ['justify'] }), ORIGIN)
    assert.match(after, /html:not\(\[data-kamayoq-measuring\]\) body p:not\(\[data-kamayoq-align\]\)/)
    assert.match(after, /\{ text-align: justify !important; hyphens: auto !important; \}/)
})

test('dado o recuo desligado e o espaço entre parágrafos escolhidos, então os dois vencem o livro', () => {
    const [, after] = stylesOf(look({ indent: false, paragraphSpacing: 8, chosen: ['indent', 'paragraphSpacing'] }), ORIGIN)
    assert.match(after, /body p:not\(\[data-kamayoq-align\]\) \{ text-indent: 0 !important; \}/)
    assert.match(after, /body p \{ margin-top: 0 !important; margin-bottom: 0\.8em !important; \}/)
})

test('dado a entrelinha escolhida, então ela vale no corpo e no texto corrido', () => {
    const [, after] = stylesOf(look({ lineHeight: 18, chosen: ['lineHeight'] }), ORIGIN)
    assert.match(after, /body, body p, body li, body dd, body blockquote \{ line-height: 1\.8 !important; \}/)
})

test('dado as margens, então a página as recebe como atributo, e o fundo do tema vai para trás do livro', () => {
    assert.equal(marginOf(look({ margin: 32 })), '32px')
    assert.equal(hostBackgroundOf(look({})), '#EFE4CC')
})

test('dado um valor que o app não manda, então a aparência inteira é recusada', () => {
    for (const bad of [
        { font: 'comic-sans' },
        { size: 30 },
        { size: 18.5 },
        { lineHeight: '1.5' },
        { background: 'red; } body { display: none' },
        { text: '#12345' },
        { chosen: ['color'] },
        { justify: 'yes' },
    ]) assert.equal(checkedLook(look(bad)), null, JSON.stringify(bad))
    assert.equal(checkedLook(null), null)
})

test('dado uma aparência certa, então ela passa sem nada a mais', () => {
    const good = look({ size: 20, chosen: ['size'] })
    assert.deepEqual(checkedLook({ ...good, extra: 'x' }), good)
})

test('dado um nome de letra herdado de Object.prototype, então a aparência é recusada', () => {
    for (const font of ['toString', 'constructor', '__proto__', 'hasOwnProperty'])
        assert.equal(checkedLook(look({ font })), null, font)
})
