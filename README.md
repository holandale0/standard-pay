# StandardPay

Simulador de plataforma de pagamentos — funciona como um PayPal ou um Stripe,
mas **nenhum dinheiro de verdade é movimentado**.

Serve para qualquer sistema que precise integrar pagamento antes de ter (ou
sem querer ter) uma conta numa plataforma real: protótipos, demonstrações para
clientes, ambientes de teste, aulas. O fluxo é o mesmo de uma plataforma real —
o sistema cria a cobrança, o pagador é redirecionado para uma página de
pagamento, e o sistema é avisado por webhook assinado — então trocar o
StandardPay por uma plataforma de verdade, depois, é trocar a integração, não
o desenho do sistema.

> Ambiente de simulação. Não há validação de cartão de verdade, não há
> liquidação, não há dinheiro. Não use para cobrar ninguém.

## O que tem

- **Contas de lojista** com chave de API (`sk_test_…`) e segredo de webhook (`whsec_…`)
- **Cobranças** em centavos, com `metadata` livre e chave de idempotência
- **Página de pagamento hospedada**, em português, com cartão de crédito e PIX simulados
- **Cartões de teste** de resultado previsível — a mesma demonstração dá sempre o mesmo resultado
- **Webhooks assinados** (HMAC-SHA256, no formato do Stripe) com reenvio automático
- **Estornos** totais e parciais
- **Expiração** de cobranças não pagas
- Documentação interativa em `/q/swagger-ui`

## Rodando

Precisa de Java 21 e Docker.

```bash
./mvnw quarkus:dev
```

O Quarkus sobe um Postgres descartável sozinho (Dev Services). A API fica em
http://localhost:8080 e a documentação em http://localhost:8080/q/swagger-ui.

Para os dados persistirem entre execuções, use o Postgres do `docker-compose.yml`:

```bash
docker compose up -d
STANDARDPAY_DB_URL=jdbc:postgresql://localhost:5433/standardpay \
STANDARDPAY_DB_USER=standardpay STANDARDPAY_DB_PASSWORD=standardpay \
./mvnw package && java -jar target/quarkus-app/quarkus-run.jar
```

Testes (sobem um Postgres de verdade, também pelo Docker):

```bash
./mvnw test
```

## Integrando em 4 passos

### 1. Crie uma conta de lojista

```bash
curl -X POST http://localhost:8080/v1/merchants \
  -H 'Content-Type: application/json' \
  -d '{"name": "Minha Loja", "webhookUrl": "https://minhaloja.example/webhooks/standardpay"}'
```

A resposta traz `apiKey` e `webhookSecret`. **A chave só aparece nesta
resposta** — guarde-a. Todas as outras chamadas usam
`Authorization: Bearer sk_test_…`.

### 2. Crie a cobrança e redirecione o pagador

```bash
curl -X POST http://localhost:8080/v1/payments \
  -H 'Authorization: Bearer sk_test_...' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-1234' \
  -d '{
    "amount": 19500,
    "description": "Pedido 1234",
    "metadata": {"orderId": "1234"},
    "returnUrl": "https://minhaloja.example/pedido/1234/obrigado",
    "cancelUrl": "https://minhaloja.example/carrinho"
  }'
```

Valores **em centavos**: `19500` é R$ 195,00. A resposta traz o `checkoutUrl`;
redirecione o pagador para lá. Se a requisição for repetida com a mesma
`Idempotency-Key`, volta a mesma cobrança — o duplo clique não cobra duas vezes.

### 3. O pagador paga

Na página, por cartão ou PIX. Depois, ele volta para o `returnUrl` com
`?payment_id=…&status=succeeded` — ou para o `cancelUrl`, se desistir.

> **Os parâmetros da volta não provam pagamento** — qualquer um digita uma URL.
> A prova é o webhook assinado, ou a consulta a `GET /v1/payments/{id}`.

### 4. Receba o webhook

```http
POST https://minhaloja.example/webhooks/standardpay
StandardPay-Signature: t=1760000000,v1=5257a869e7...

{
  "id": "evt_...",
  "type": "payment.succeeded",
  "createdAt": "2026-10-14T02:30:00Z",
  "data": { "id": "pay_...", "amount": 19500, "status": "succeeded", "metadata": {"orderId": "1234"}, ... }
}
```

Confira a assinatura antes de confiar no conteúdo (exemplo em Node):

```js
const [t, v1] = req.headers['standardpay-signature'].split(',').map(p => p.split('=')[1]);
const esperado = crypto.createHmac('sha256', process.env.STANDARDPAY_WEBHOOK_SECRET)
  .update(`${t}.${corpoCru}`)          // o corpo exatamente como chegou, antes de JSON.parse
  .digest('hex');
const valido = crypto.timingSafeEqual(Buffer.from(esperado), Buffer.from(v1))
  && Math.abs(Date.now() / 1000 - Number(t)) < 300;   // recuse assinaturas velhas
```

Responda com qualquer 2xx. Sem resposta, ou com erro, o StandardPay tenta de
novo: 1 min, 5 min, 30 min, 2 h, 6 h, 12 h, 24 h — 8 tentativas no total. Um
mesmo evento pode chegar mais de uma vez; use o `id` (`evt_…`) para ignorar
repetições.

| Evento | Quando |
|---|---|
| `payment.succeeded` | O pagador pagou |
| `payment.failed` | Uma tentativa de cartão foi recusada (a cobrança continua aberta) |
| `payment.canceled` | O lojista ou o pagador cancelou |
| `payment.expired` | Ninguém pagou dentro do prazo (30 min por padrão) |
| `refund.succeeded` | Um estorno foi feito |

## Cartões de teste

| Número | Resultado |
|---|---|
| `4242 4242 4242 4242` | Aprovado |
| `5555 5555 5555 4444` | Aprovado |
| `4000 0000 0000 0002` | Recusado (`card_declined`) |
| `4000 0000 0000 9995` | Saldo insuficiente (`insufficient_funds`) |
| `4000 0000 0000 0069` | Cartão vencido (`expired_card`) |
| `4000 0000 0000 0127` | CVV incorreto (`incorrect_cvc`) |

Qualquer outro número com dígito verificador válido é aprovado. Validade e CVV
não são conferidos. O PIX tem um botão "Simular pagamento do PIX", que faz o
papel do banco avisando que o dinheiro caiu.

Um cartão recusado não encerra a cobrança: ela continua `pending` e o pagador
pode tentar outro cartão.

## API

| Método | Caminho | Para quê |
|---|---|---|
| `POST` | `/v1/merchants` | Criar conta (sem autenticação) |
| `GET` / `PATCH` | `/v1/merchants/me` | Ver a conta / trocar o `webhookUrl` |
| `POST` | `/v1/merchants/me/webhook-secret` | Trocar o segredo dos webhooks |
| `POST` | `/v1/payments` | Criar cobrança |
| `GET` | `/v1/payments` | Listar (`?status=succeeded&limit=50`) |
| `GET` | `/v1/payments/{id}` | Consultar |
| `POST` | `/v1/payments/{id}/cancel` | Cancelar enquanto pendente |
| `POST` | `/v1/payments/{id}/refunds` | Estornar (sem `amount`, estorna tudo o que resta) |
| `GET` | `/v1/payments/{id}/refunds` | Estornos da cobrança |
| `GET` | `/v1/webhook-deliveries` | O que foi enviado, e o que aconteceu em cada envio |
| `POST` | `/v1/webhook-deliveries/{id}/retry` | Enviar de novo |

Todo erro sai no mesmo formato, com um `code` estável para o programa decidir
o que fazer e uma `message` para quem está depurando:

```json
{ "error": { "code": "amount_too_large", "message": "Refund amount 20000 exceeds the refundable 19500 (amounts are in cents)." } }
```

## Configuração

| Variável | Padrão | |
|---|---|---|
| `STANDARDPAY_PUBLIC_URL` | `http://localhost:8080` | Endereço público; entra no `checkoutUrl`. Precisa ser alcançável pelo pagador. |
| `STANDARDPAY_DB_URL`, `_USER`, `_PASSWORD` | — | Postgres, em produção |
| `STANDARDPAY_CHECKOUT_EXPIRES_IN` | `PT30M` | Prazo da cobrança (ISO-8601) |
| `STANDARDPAY_WEBHOOKS_POLL_INTERVAL` | `5s` | De quanto em quanto tempo os webhooks pendentes saem |
| `STANDARDPAY_WEBHOOKS_MAX_ATTEMPTS` | `8` | Tentativas antes de desistir |
| `STANDARDPAY_WEBHOOKS_TIMEOUT` | `PT10S` | Quanto esperar a resposta do lojista |
| `PORT` | `8080` | Porta HTTP |

## Arquitetura

Um serviço só — Quarkus 3, Java 21, Postgres com migrations Flyway — organizado
em **BCE (Boundary-Control-Entity)** por componente de negócio:

```
org.leonardo.standardpay
├── merchants   contas e autenticação por chave de API
├── payments    cobranças, cartões de teste, expiração
├── checkout    a página do pagador (templates Qute)
├── refunds     estornos
├── webhooks    fila de eventos, assinatura e entrega
└── platform    ids, formato de erro
```

Em cada componente, `boundary` é a borda HTTP, `control` são as regras e
`entity` é o que vai para o banco.

Decisões que valem saber:

- **Webhooks pela tabela, não por fila de mensagens** (padrão *outbox*). O
  evento é gravado na mesma transação que muda o pagamento: não existe pagamento
  aprovado cujo aviso se perdeu, nem aviso de pagamento que não foi gravado. Um
  agendador envia os pendentes.
- **Linha travada antes de mudar estado.** Dois cliques simultâneos em "Pagar",
  ou dois estornos ao mesmo tempo, não passam os dois.
- **Chave de API guardada como SHA-256.** Quem lê o banco não consegue se passar
  pelo lojista.
- **Valores em centavos**, como nas plataformas reais.

## Próximos passos

- Split: dividir o valor entre recebedores, com saldo e extrato por recebedor
- Simular falhas de estorno e estornos que levam dias
- Painel web do lojista (hoje, tudo é pela API)
- Imagem publicada para rodar com um `docker run`

## Licença

[MIT](LICENSE)
