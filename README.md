# Impressora ESC/POS – Serviço de Impressão Android

Imprime na SP-POS894 (ou qualquer impressora térmica ESC/POS) pela rede (TCP 9100), por
USB ou por Bluetooth, de duas formas:

1. **Impressão silenciosa por HTTP** — o Odoo POS (Chrome) ou uma app React Native chamam
   um endpoint local (`POST /print`) e o recibo sai na impressora **sem qualquer diálogo**.
   É o modo recomendado para um POS. Ver secção [Impressão silenciosa](#impressão-silenciosa-por-http-sem-diálogo).
2. **Diálogo "Imprimir" do Android** — via `PrintService`, faz a impressora aparecer no
   diálogo de impressão de qualquer app (Chrome, visualizadores de PDF, etc.). Mantido como
   alternativa para quando se quer mesmo o diálogo do sistema.

Como funciona (ambos os modos): o PDF recebido é renderizado a 203 dpi com a largura do
rolo (576 pontos em 80 mm, 384 em 58 mm) → convertido para preto/branco → enviado com o
comando raster `GS v 0` → avanço, corte (`GS V 66`) e, opcionalmente, gaveta. Como é tudo
imagem, acentos e tipos de letra saem sempre correctos. A impressão silenciosa também aceita
bytes ESC/POS já prontos (sem passar pelo rasterizador), para apps que preferem construir o
recibo directamente em comandos ESC/POS.

Ligações suportadas: **USB (OTG)**, **Ethernet (TCP 9100)** e **Bluetooth (SPP)**. O
Bluetooth serve também para terminais Android com impressora embutida que a expõem como
impressora Bluetooth virtual (ex.: ZCS Z92).

## Compilar (sem Android Studio)

Requisitos: JDK 17+ (o Java 21 serve) e o Android SDK que já usa para o React Native.

1. Indique onde está o SDK, criando `local.properties` na raiz do projecto
   (ou tendo `ANDROID_HOME` definido):
   ```
   sdk.dir=/caminho/para/Android/Sdk
   ```
   Windows: `sdk.dir=C\:\\Users\\SEU_USER\\AppData\\Local\\Android\\Sdk`
2. Confirme que existe `platforms/android-35` no SDK. Se só tiver `android-34`,
   altere `compileSdk 35` para `34` em `app/build.gradle`.
3. Compile:
   ```
   ./gradlew assembleDebug        # Linux/macOS
   gradlew.bat assembleDebug      # Windows
   ```
   Na primeira vez descarrega o Gradle 8.10.2 e o Android Gradle Plugin (precisa de internet).
4. Instale no telemóvel (com depuração USB activa):
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Configurar no telemóvel

1. Abra a app **Impressora ESC/POS** → "Abrir definições de impressão" →
   active o serviço **Impressora ESC/POS**.
2. Adicione a impressora:
   - **Rede**: escreva o IP da impressora (imprima a página de auto-teste da impressora
     para ver o IP; mantenha o botão FEED premido ao ligar) e porta 9100.
   - **USB**: ligue a impressora com um cabo/adaptador OTG → "Procurar dispositivos USB" →
     "Usar esta impressora" → aceite a permissão. Se o Android perguntar se quer abrir a app
     ao ligar a impressora, marque **Usar sempre** (a permissão fica permanente).
   - **Bluetooth**: emparelhe primeiro a impressora nas definições do Android (a impressora
     interna de terminais como o ZCS Z92 já vem emparelhada) → "Procurar impressoras
     Bluetooth" → autorize **Dispositivos próximos** → "Usar esta impressora". Escolha antes a
     largura do papel (estes terminais usam 58 mm).
3. Toque em **Imprimir teste**.

## Impressão silenciosa por HTTP (sem diálogo)

Na app **Impressora ESC/POS** → secção "Impressão directa (HTTP local, sem diálogo)":

1. Configure pelo menos uma impressora (rede, USB ou Bluetooth) e, se tiver mais do que uma, toque em
   **Tornar predefinida** na que deve ser usada quando o pedido não indicar qual usar.
2. Active **"Activar servidor local"**, ajuste a porta se necessário (predefinição `9200`) e,
   opcionalmente, defina uma **chave de API** (fica exigida no cabeçalho `X-Api-Key` de todos
   os pedidos). Toque em **Guardar e aplicar**.
3. O ecrã mostra o(s) endereço(s) a usar, por exemplo `http://192.168.1.23:9200/print`.

O serviço corre em primeiro plano (notificação persistente) e reinicia sozinho depois de o
aparelho reiniciar, desde que tenha ficado activado.

### Contrato da API

| Método | Caminho | Descrição |
|---|---|---|
| `GET`  | `/health` | `{"status":"ok"}` — verificação simples de que o serviço está no ar. |
| `GET`  | `/printers` | Lista as impressoras configuradas: `[{"id":...,"name":...,"type":"net"\|"usb"\|"bt","widthMm":80,"default":true}]`. |
| `POST` | `/print` ou `/print?printer=ID` | Imprime o corpo do pedido. Sem `printer=`, usa a impressora predefinida (falha com `400` se não houver nenhuma definida e existir mais do que uma configurada). |

Autenticação (se tiver definido uma chave de API): cabeçalho `X-Api-Key: <chave>` em todos os
pedidos — caso contrário responde `401`.

O corpo de `POST /print` é interpretado pelo `Content-Type`:

- **`application/pdf`** — o PDF é rasterizado exactamente como no modo por diálogo (acentos e
  tipos de letra perfeitos, corte/gaveta/avanço conforme as "Opções de impressão" da app).
  Ideal para o Odoo, que já sabe gerar um PDF do recibo/relatório.
- **Qualquer outro `Content-Type`** (ex.: `application/octet-stream`) — os bytes são enviados
  **tal e qual** para a impressora, sem qualquer processamento. O chamador é responsável por
  gerar os comandos ESC/POS completos, incluindo corte/gaveta se os quiser (as "Opções de
  impressão" da app não se aplicam a este modo). Ideal para a app React Native.

Respostas: `200 {"status":"ok"}` em caso de sucesso; `400` pedido inválido/impressora não
encontrada; `401` chave de API inválida; `502` falha ao comunicar com a impressora; `504`
tempo esgotado (impressora desligada, IP errado, etc.).

### Exemplos

```bash
# Verificar que está no ar
curl http://192.168.1.23:9200/health

# Listar impressoras configuradas
curl http://192.168.1.23:9200/printers

# Imprimir um PDF
curl -X POST http://192.168.1.23:9200/print \
  -H "Content-Type: application/pdf" \
  -H "X-Api-Key: a-sua-chave" \
  --data-binary @recibo.pdf

# Imprimir bytes ESC/POS já prontos
curl -X POST "http://192.168.1.23:9200/print?printer=net:192.168.1.100:9100" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @recibo.escpos
```

**Odoo POS (Chrome), a partir de um PDF de recibo:**

```javascript
async function printSilently(pdfBlob) {
    const res = await fetch("http://127.0.0.1:9200/print", {
        method: "POST",
        headers: { "Content-Type": "application/pdf" },
        body: pdfBlob,
    });
    if (!res.ok) throw new Error("Falha ao imprimir: " + (await res.text()));
}
```

Use sempre `http://127.0.0.1:9200` (não o IP da rede) quando o Chrome corre **no mesmo
telemóvel** que este serviço: os browsers bloqueiam pedidos "mixed content" (HTTP a partir de
uma página HTTPS) para qualquer IP da rede local, mas abrem uma excepção para `localhost`/
`127.0.0.1`. Se o Odoo POS correr noutro aparelho, terá de usar o IP de rede e ou servir o
Odoo por HTTP, ou aceitar que o browser bloqueie o pedido.

**App React Native, com bytes ESC/POS já construídos:**

```javascript
async function printSilently(escposBytes /* Uint8Array */) {
    const res = await fetch("http://127.0.0.1:9200/print", {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: escposBytes,
    });
    if (!res.ok) throw new Error("Falha ao imprimir: " + (await res.text()));
}
```

Numa app nativa não há restrições de "mixed content", mas o Android bloqueia tráfego
"cleartext" (HTTP) por predefinição a partir do Android 9: se a app React Native chamar um IP
de rede (aparelho diferente), active `usesCleartextTraffic`/`networkSecurityConfig` no
`AndroidManifest.xml` **dessa** app para o domínio/IP da impressora.

## Imprimir a partir do Odoo (Chrome no Android)

- **POS / talões**: botão "Imprimir recibo" → abre o diálogo do Chrome → escolha a
  impressora "ESC/POS" e o tamanho **Talão 80 × 297 mm** (o espaço em branco no fim é cortado
  automaticamente; para talões muito longos use 80 × 600 mm).
- **Relatórios/facturas em PDF**: o Chrome no Android descarrega o PDF; abra-o
  (Ficheiros / Drive) → ⋮ → Imprimir → escolha a impressora. Documentos A4 são reduzidos à
  largura do rolo, por isso a letra fica pequena; o ideal são relatórios desenhados para 80 mm.

## Imprimir a partir das suas apps React Native

- **Silenciosa (recomendado para POS)**: use a API HTTP da secção
  [Impressão silenciosa](#impressão-silenciosa-por-http-sem-diálogo) — `fetch()` para
  `http://127.0.0.1:9200/print` com os bytes ESC/POS ou um PDF. Sem diálogo nenhum.
- **Com o diálogo do Android**: qualquer biblioteca que use o sistema de impressão do Android
  funciona, por exemplo `react-native-print` (`RNPrint.print({ html })`) ou `expo-print`
  (`Print.printAsync({ html })`). Use HTML com largura ~72 mm
  (ex.: `<body style="width:72mm;margin:0">`) e escolha o tamanho de talão no diálogo.

## Resolução de problemas

- **A impressora não aparece no diálogo**: confirme que o serviço está activo e que a
  impressora foi adicionada na app. Em "Todas as impressoras" pode ser preciso escolhê-la.
- **USB "não está ligada"**: o telemóvel tem de suportar OTG; algumas impressoras
  precisam de estar ligadas à corrente antes do cabo USB.
- **USB pede permissão sempre**: a impressora pode não se identificar como classe 7.
  Acrescente o VID/PID (em decimal, mostrado na app) em `app/src/main/res/xml/device_filter.xml`
  e recompile.
- **Bluetooth "não foi possível ligar"**: confirme que a impressora está ligada, emparelhada
  e que nenhuma outra app está ligada a ela (o Bluetooth série só aceita uma ligação de cada
  vez).
- **Texto cinzento/fraco**: desligue o "Modo imagem"; para logótipos ligue-o.
- **Rede não liga**: telemóvel e impressora têm de estar na mesma rede; teste com
  `ping IP` num computador.
- **Servidor silencioso não responde**: confirme que "Activar servidor local" está ligado e
  que tocou em "Guardar e aplicar"; teste com `curl http://IP:PORTA/health` a partir doutro
  aparelho na mesma rede. Se o Android matar o serviço em segundo plano, desactive a
  optimização de bateria para esta app.
- **Chrome bloqueia o pedido ("mixed content" / falha de rede silenciosa)**: se o Odoo POS
  for servido por HTTPS e este serviço estiver no mesmo telemóvel, use sempre
  `http://127.0.0.1:PORTA` em vez do IP da rede.
- Logs: `adb logcat -s EscPosPrintService EscPosBridge`
