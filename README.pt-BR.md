# Kamayoq

[English](README.md) · Português (Brasil)

Um app de biblioteca e leitura para Android: quadrinhos (mangá, manhwa, webtoon), novels e livros, a partir das suas próprias pastas, com um servidor [Kavita](https://www.kavitareader.com) opcional.

> **Estado: no começo do desenvolvimento.** Ainda não serve para a leitura do dia a dia. Hoje já dá para ler capítulos de imagem (CBZ e pastas de imagens), das suas pastas ou do seu servidor Kavita; o EPUB vem a seguir.

## Funções

Já no código:

- Deixa escolher uma pasta-base e lê as obras da pasta `Library` dela pela [convenção de pastas](docs/decisions/folder-convention-2026-10-24.md): pastas de tipo (`Comics`, `Novels`, `Books`), uma pasta por obra, capítulos como arquivos CBZ/ZIP ou pastas de imagens, em ordem de volume e número do capítulo.
- Mostra as obras e seus capítulos e lê capítulos de imagem numa tira vertical.
- Guarda a posição de leitura e marca o capítulo como lido ao chegar ao fim, pelo [contrato do progresso](docs/decisions/progress-contract-2026-10-24.md).
- Grava na pasta `Backups` um backup de tudo, menos os arquivos das obras. Restaurar ainda não existe.
- Conecta ao seu próprio servidor Kavita com o endereço e a chave de API: mostra as bibliotecas que você incluir, baixa capítulos para ler sem rede e mantém a posição de leitura igual nos dois lados, perguntando quando os dois mudaram.

Previsto, nesta ordem: EPUB e outros formatos de texto pelo [foliate-js](https://github.com/readest/foliate-js), depois a base visual e as extensões próprias do app.

## Baixar

Só versões de teste. Cada mudança na `main` que passa no CI é publicada como pré-lançamento chamado `r<número>`, em que o número é a contagem de commits. O [Obtainium](https://github.com/ImranR98/Obtainium) acompanha essas versões.

*Requer Android 12 ou superior.*

## Compilar

O app é em Kotlin e Jetpack Compose. O `core` é Kotlin puro, sem classes do Android, e por isso as regras são testadas na JVM; o `app` é o aplicativo Android.

Com o JDK 21:

```
./gradlew :core:test             # testes das regras (JVM)
./gradlew :data:test             # testes do armazenamento (JVM, SQLite)
./gradlew :app:testDebugUnitTest # testes do app (Robolectric)
./gradlew :app:lintDebug         # lint do Android
./gradlew :app:assembleDebug     # APK de depuração
```

O CI roda os mesmos cinco passos em todo envio e pull request.

## Organização

| Caminho | O que guarda |
|---|---|
| `core/` | Regras em Kotlin puro: convenção de pastas, progresso de leitura, formato do backup |
| `data/` | O que o app guarda e busca: o banco, o backup e o cliente do Kavita, em Kotlin puro |
| `app/` | O aplicativo Android |
| `docs/decisions/` | Decisões técnicas, cada uma com as fontes e o que foi descartado |
| `docs/research/` | Pesquisa por trás dos passos maiores (Kavita, EPUB, testes) |

## Contribuir

Ainda não está aberto a contribuições de código; veja [CONTRIBUTING.md](CONTRIBUTING.md). Problemas de segurança: veja [SECURITY.md](SECURITY.md).

## Aviso

O Kamayoq não hospeda nenhum conteúdo. Ele lê arquivos que você já tem e, se você configurar um, o seu próprio servidor Kavita.

## Licença

O Kamayoq é software livre: você pode redistribuí-lo e/ou modificá-lo nos termos da Licença Pública Geral GNU, publicada pela Free Software Foundation, na versão 3 da Licença ou (a seu critério) em qualquer versão posterior. Veja [LICENSE](LICENSE).
