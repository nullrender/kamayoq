// The reader's error report, run by Node: errors.js is a plain script, so it runs in a context of its own.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'

const source = readFileSync(new URL('../main/assets/reader/errors.js', import.meta.url), 'utf8')

const loaded = () => {
    const listeners = {}
    const sent = []
    vm.runInNewContext(source, {
        addEventListener: (type, listener) => { listeners[type] = listener },
        kamayoqBridge: { postMessage: message => sent.push(JSON.parse(message)) },
    })
    return { listeners, sent }
}

// Desktop Chromium raised it right after the Gutenberg Dom Casmurro opened (08/11/2026), and the
// page reported a failure over a book that was showing.
test('dado o aviso de laço do ResizeObserver, então não é falha da página', () => {
    const { listeners, sent } = loaded()
    listeners.error({ message: 'ResizeObserver loop completed with undelivered notifications.' })
    listeners.error({ message: 'ResizeObserver loop limit exceeded' })
    assert.deepEqual(sent, [])
})

test('dado um erro de script, então a página relata a falha', () => {
    const { listeners, sent } = loaded()
    listeners.error({ message: 'Uncaught TypeError: x is not a function' })
    assert.deepEqual(sent, [{ type: 'failed', code: 'page-error' }])
})

test('dado uma promessa rejeitada sem tratamento, então a página relata a falha', () => {
    const { listeners, sent } = loaded()
    listeners.unhandledrejection({ reason: new Error('Entry refused') })
    assert.deepEqual(sent, [{ type: 'failed', code: 'page-error' }])
})
