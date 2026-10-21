# Na Feira Pod Studio 🎙️

App Android de gravação de podcast do **Rafael Rezende / Na Feira Pod**,
no estilo do Spreaker Studio — mas **sem limite de tempo de gravação**.

No Spreaker Studio a gravação para aos 15 minutos. Aqui não: a gravação
só para quando você tocar em **Parar**. O único limite é o espaço livre
do aparelho (1 hora de gravação ocupa aproximadamente 55 MB).

## O que a V1 faz

- ⏺️ **Gravação ilimitada** em `.m4a` (AAC, 44,1 kHz, 128 kbps), com
  **pausar e continuar** na mesma faixa — e o cronômetro pausa junto.
- 🎚️ **Medidor de nível** do microfone em tempo real, pra não estourar
  nem ficar baixo demais.
- 🎤 **Escolha da entrada de áudio**: microfone do aparelho, lapela,
  microfone USB/Bluetooth ou mesa de som — o que estiver conectado
  aparece na lista.
- 📱 Funciona em **celular e tablet**, tudo **offline**, com a tela
  acesa durante a gravação e notificação persistente (a gravação
  continua mesmo com o app minimizado).
- 📚 **Biblioteca** dos episódios no próprio aparelho, com data,
  duração e tamanho de cada arquivo.
- ▶️ **Ouvir**, **compartilhar** (WhatsApp, Drive, e-mail…),
  **renomear** e **apagar** cada gravação, direto da biblioteca.

## V2 — Mesa de efeitos sonoros 🎛️

Igual ao Spreaker Studio: uma grade de **12 botões (pads)** de efeitos
que entram **misturados digitalmente na gravação**, na hora, junto com
a sua voz — não é o alto-falante sendo captado pelo microfone.

- 🔊 **8 sons de fábrica** gerados pelo próprio app (sem arquivos):
  aplausos, risadas, campainha, ding, "ba dum tss", buzina, transição
  (whoosh) e vinheta.
- 🎚️ **Volume dos efeitos** separado, com botão **Parar efeitos**.
- 🎧 Fora da gravação, tocar num pad **ouve o som** (preview).

### Como personalizar um pad

**Segure o pad** (toque longo) para abrir as opções:

- **Trocar som (arquivo do aparelho)** — escolha qualquer áudio
  (MP3, WAV, M4A…) do celular/tablet; ele é copiado pro app e vale
  pra esse pad. Dá pra deixar um pad com a vinheta do seu podcast!
- **Renomear** — mude o texto do botão.
- **Trocar cor** — 8 cores pra organizar a mesa do seu jeito.
- **Restaurar padrão** (pads de fábrica) ou **Esvaziar pad**
  (pads com arquivo seu).

Toque curto num pad vazio (fora da gravação) também abre o seletor
de arquivo direto.

> ⚠️ **Use fone de ouvido ao tocar efeitos gravando.** O efeito entra
> na gravação pela mixagem digital, mas se ele sair no alto-falante,
> o microfone capta o som de novo pelo ar e o efeito fica duplicado.

### Motor de gravação novo

A V2 trocou o gravador pronto do Android (`MediaRecorder`, que só
capta o microfone) por um **motor próprio de mixagem**: captura em
PCM (`AudioRecord`), soma voz + efeitos com controle de ganho e
codifica em AAC (`MediaCodec` + `MediaMuxer`) pro mesmo `.m4a` de
antes. Pausar/continuar, medidor de nível e gravação sem limite
continuam iguais.

## V2.1 — Telas em abas 📑

Correção de layout pro celular: tudo numa tela só passava da altura
em telas menores, a lista da biblioteca era espremida até sumir e as
gravações "desapareciam" (estavam lá, só não apareciam).

- A tela agora tem **duas abas no topo: ⏺ Gravar e 📚 Biblioteca**
  (a aba da biblioteca mostra o número de gravações).
- **Gravar**: todos os controles (título, cronômetro, microfone,
  botões, mesa de efeitos e volume) numa página com rolagem — tudo
  alcançável em qualquer tela.
- **Biblioteca**: a lista ocupa a **tela cheia**, com ouvir,
  compartilhar, renomear e apagar como antes.
- Ao parar uma gravação, um aviso confirma: *"Gravação salva —
  veja na aba Biblioteca"*.
- Pads da mesa com **altura uniforme e nome em uma linha só** —
  acabou o botão desalinhado.

## O que fica pra depois

- Cortar/aparar o áudio dentro do app
- Música de fundo com *auto-ducking* ao vivo
- Publicar direto no Spotify / Apple Podcasts

## Como compilar (GitHub Actions)

O projeto **não** inclui o Gradle Wrapper de propósito — o workflow
provisiona o Gradle 8.7, igual aos outros apps do Rafael (Teleprompter,
PromptBox).

1. Suba este projeto para um repositório no GitHub (branch `main`).
   - ⚠️ **Não** suba nenhum arquivo em `.github/workflows/` pelo push
     da API — o app do GitHub não tem permissão de Workflows.
2. No site do GitHub, crie o arquivo `.github/workflows/build-apk.yml`
   pela interface web e cole nele o conteúdo de
   **`BUILD-WORKFLOW.yml.txt`** (que está na raiz deste projeto).
3. O push desse arquivo já dispara o build. Acompanhe na aba
   **Actions** do repositório.
4. Ao final, baixe o artefato **`podcast-studio-apk`**
   (`app-debug.apk`) e instale no celular/tablet (permita "instalar
   de fontes desconhecidas" se o Android pedir).

Também dá pra rodar o build manualmente a qualquer momento pela aba
**Actions → Build APK → Run workflow**.

## Dados técnicos

- Pacote: `br.com.rafarezende.podcaststudio`
- Android mínimo: 8.0 (API 26) · Alvo: Android 14 (API 34)
- Kotlin + Views/XML (sem Compose), AGP 8.5.2, Kotlin 2.0.20
- Gravações salvas em: pasta do app em `Music/recordings`
  (área externa privada do app — desinstalar o app apaga as gravações;
  compartilhe/exporte o que for importante!)

---
Feito para o **Na Feira Pod** · DX Labs · #digaxiiiss
