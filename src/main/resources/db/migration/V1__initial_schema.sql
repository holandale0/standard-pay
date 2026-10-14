-- Esquema inicial do StandardPay.
--
-- Valores sempre em centavos (bigint), como nas plataformas reais: dinheiro em
-- ponto flutuante acumula erro de arredondamento, e decimal obriga cada
-- cliente a acertar a escala.

CREATE TABLE merchant (
    id              varchar(40)  PRIMARY KEY,
    name            varchar(120) NOT NULL,
    -- Só o hash: quem lê o banco não consegue se passar pelo lojista.
    api_key_hash    char(64)     NOT NULL UNIQUE,
    api_key_last4   varchar(4)   NOT NULL,
    webhook_url     varchar(500),
    -- Precisa ficar em claro: é com ele que cada webhook é assinado.
    webhook_secret  varchar(80)  NOT NULL,
    created_at      timestamptz  NOT NULL
);

CREATE TABLE payment (
    id               varchar(40)   PRIMARY KEY,
    merchant_id      varchar(40)   NOT NULL REFERENCES merchant (id),
    amount           bigint        NOT NULL CHECK (amount > 0),
    currency         char(3)       NOT NULL,
    description      varchar(500),
    metadata         jsonb         NOT NULL DEFAULT '{}',
    status           varchar(20)   NOT NULL,
    method           varchar(10),
    card_last4       varchar(4),
    failure_code     varchar(40),
    failure_message  varchar(200),
    amount_refunded  bigint        NOT NULL DEFAULT 0 CHECK (amount_refunded >= 0),
    return_url       varchar(1000),
    cancel_url       varchar(1000),
    idempotency_key  varchar(200),
    expires_at       timestamptz   NOT NULL,
    paid_at          timestamptz,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT payment_refund_within_amount CHECK (amount_refunded <= amount),
    -- A mesma chave, do mesmo lojista, é a mesma cobrança: o duplo clique não cobra duas vezes.
    CONSTRAINT payment_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX payment_merchant_created ON payment (merchant_id, created_at DESC);
-- A varredura de expiração só olha as pendentes.
CREATE INDEX payment_pending_expiry ON payment (expires_at) WHERE status = 'PENDING';

CREATE TABLE refund (
    id          varchar(40)  PRIMARY KEY,
    payment_id  varchar(40)  NOT NULL REFERENCES payment (id),
    amount      bigint       NOT NULL CHECK (amount > 0),
    reason      varchar(500),
    status      varchar(20)  NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX refund_payment ON refund (payment_id);

-- Fila de webhooks no próprio banco (padrão outbox): o evento é gravado na
-- mesma transação que muda o pagamento, então não existe pagamento aprovado
-- cujo aviso se perdeu, nem aviso de um pagamento que não foi gravado.
CREATE TABLE webhook_delivery (
    id                varchar(40)   PRIMARY KEY,
    merchant_id       varchar(40)   NOT NULL REFERENCES merchant (id),
    event_type        varchar(60)   NOT NULL,
    target_url        varchar(500)  NOT NULL,
    -- Texto, e não jsonb: a assinatura é calculada sobre estes bytes exatos.
    payload           text          NOT NULL,
    status            varchar(20)   NOT NULL,
    attempts          integer       NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz   NOT NULL,
    last_status_code  integer,
    last_error        varchar(500),
    delivered_at      timestamptz,
    created_at        timestamptz   NOT NULL
);

CREATE INDEX webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_delivery_merchant ON webhook_delivery (merchant_id, created_at DESC);
