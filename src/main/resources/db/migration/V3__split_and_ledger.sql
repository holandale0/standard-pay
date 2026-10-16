-- Split: uma cobrança dividida entre recebedores, com saldo e extrato por conta.
--
-- Cada lojista tem a própria conta (recipient_id nulo nos lançamentos) e as dos
-- recebedores que cadastrar. O saldo não é guardado: é a soma dos lançamentos,
-- e cada lançamento diz desde quando está disponível. Assim o saldo pendente
-- vira disponível sozinho, sem varredura, e nunca diverge do extrato.

CREATE TABLE recipient (
    id           varchar(40)  PRIMARY KEY,
    merchant_id  varchar(40)  NOT NULL REFERENCES merchant (id),
    name         varchar(120) NOT NULL,
    metadata     jsonb        NOT NULL DEFAULT '{}',
    created_at   timestamptz  NOT NULL
);

CREATE INDEX recipient_merchant_created ON recipient (merchant_id, created_at DESC);

-- A parte de cada recebedor numa cobrança. O que sobra é do lojista.
CREATE TABLE payment_split (
    payment_id    varchar(40) NOT NULL REFERENCES payment (id),
    recipient_id  varchar(40) NOT NULL REFERENCES recipient (id),
    amount        bigint      NOT NULL CHECK (amount > 0),
    PRIMARY KEY (payment_id, recipient_id)
);

-- O extrato. Valor com sinal: crédito positivo, débito negativo.
CREATE TABLE ledger_entry (
    id            varchar(40)  PRIMARY KEY,
    merchant_id   varchar(40)  NOT NULL REFERENCES merchant (id),
    -- Nulo: a conta do próprio lojista.
    recipient_id  varchar(40)  REFERENCES recipient (id),
    type          varchar(20)  NOT NULL,
    amount        bigint       NOT NULL CHECK (amount <> 0),
    currency      char(3)      NOT NULL,
    payment_id    varchar(40)  NOT NULL REFERENCES payment (id),
    refund_id     varchar(40)  REFERENCES refund (id),
    available_at  timestamptz  NOT NULL,
    created_at    timestamptz  NOT NULL
);

CREATE INDEX ledger_entry_account ON ledger_entry (merchant_id, recipient_id, created_at DESC);
CREATE INDEX ledger_entry_payment ON ledger_entry (payment_id);

-- O que já aconteceu antes do extrato existir entra nele, na conta do lojista e
-- já disponível: cobranças pagas e os estornos que não falharam.
INSERT INTO ledger_entry (id, merchant_id, recipient_id, type, amount, currency, payment_id, refund_id,
                          available_at, created_at)
SELECT 'txn_' || substr(md5(random()::text || p.id), 1, 24), p.merchant_id, NULL, 'PAYMENT', p.amount,
       p.currency, p.id, NULL, p.paid_at, p.paid_at
FROM payment p
WHERE p.status = 'SUCCEEDED';

INSERT INTO ledger_entry (id, merchant_id, recipient_id, type, amount, currency, payment_id, refund_id,
                          available_at, created_at)
SELECT 'txn_' || substr(md5(random()::text || r.id), 1, 24), p.merchant_id, NULL, 'REFUND', -r.amount,
       p.currency, p.id, r.id, r.created_at, r.created_at
FROM refund r
JOIN payment p ON p.id = r.payment_id
WHERE r.status IN ('SUCCEEDED', 'PENDING');
