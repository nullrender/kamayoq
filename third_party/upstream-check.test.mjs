// Keeps third_party/foliate-js honest: what is here is what UPSTREAM.md says came from the fork,
// and every file that differs from it has a line saying why (docs/research/epub-step-4-design.md, piece 4).
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'

const copy = join(import.meta.dirname, 'foliate-js')
const upstream = join(copy, 'UPSTREAM.md')

const sha256 = file => createHash('sha256').update(readFileSync(join(copy, file))).digest('hex')

// Rows of the table under a heading, as lists of cells, without the header and its separator.
const tableUnder = (heading, text) => {
    const section = text.split(`## ${heading}\n`)[1]
    assert.ok(section !== undefined, `UPSTREAM.md has no section "${heading}"`)
    return section.split('\n## ')[0].split('\n')
        .filter(line => line.startsWith('|'))
        .slice(2)
        .map(line => line.split('|').slice(1, -1).map(cell => cell.trim().replaceAll('`', '')))
}

// Only imports that run when a module loads; `import()` of a format left out runs only if asked for.
const importsIn = text => [...text
    // Across lines too: `import {\n a,\n} from './x.js'` is one import.
    .matchAll(/^\s*(?:import|export)\b[^'"(;=]*?\bfrom\s*['"]([^'"]+)['"]|^\s*import\s*['"]([^'"]+)['"]/gm)]
    .map(match => match[1] ?? match[2])

const staticImports = file => importsIn(readFileSync(join(copy, file), 'utf8'))

test('dado um import ou export em várias linhas, quando leio os imports, então ele também é visto', () => {
    const text = "import {\n    a,\n    b,\n} from './missing.js'\nimport { c } from './ok.js'\nexport {\n    d } from './again.js'\nexport const fromThere = x => x\n"
    assert.deepEqual(importsIn(text), ['./missing.js', './ok.js', './again.js'])
})

test('dado o UPSTREAM.md, quando confiro os arquivos, então cada um existe e nenhum difere sem linha na tabela', () => {
    const text = readFileSync(upstream, 'utf8')
    const files = tableUnder('Files', text)
    const changed = tableUnder('Changes', text).map(([file]) => file)
    assert.ok(files.length > 0, 'the file table is empty')
    for (const [file, original, current] of files) {
        assert.ok(existsSync(join(copy, file)), `${file} is listed and missing`)
        assert.equal(sha256(file), current, `${file} changed without updating UPSTREAM.md`)
        if (current !== original) assert.ok(changed.includes(file), `${file} differs from the fork with no line in Changes`)
    }
})

test('dado a pasta da cópia, quando listo os arquivos, então nenhum está fora do UPSTREAM.md', () => {
    const listed = tableUnder('Files', readFileSync(upstream, 'utf8')).map(([file]) => file).sort()
    const present = readdirSync(copy).filter(file => file !== 'UPSTREAM.md').sort()
    assert.deepEqual(present, listed)
})

test('dado os módulos copiados, quando leio os imports estáticos, então cada import relativo aponta para arquivo copiado', () => {
    const modules = readdirSync(copy).filter(file => file.endsWith('.js'))
    assert.ok(modules.length > 0, 'no modules copied')
    for (const file of modules) {
        for (const target of staticImports(file).filter(name => name.startsWith('.'))) {
            assert.ok(existsSync(join(dirname(join(copy, file)), target)), `${file} imports ${target}, which was not copied`)
        }
    }
})
