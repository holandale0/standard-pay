-- Estornos que levam tempo e que podem falhar.
--
-- Até aqui todo estorno concluía na hora. Agora ele pode ficar pendente e
-- concluir (ou falhar) depois; o comportamento vem do cartão de teste usado no
-- pagamento, guardado na cobrança.

ALTER TABLE payment ADD COLUMN refund_behavior varchar(10) NOT NULL DEFAULT 'INSTANT';

ALTER TABLE refund
    ADD COLUMN failure_code    varchar(40),
    ADD COLUMN failure_message varchar(200),
    -- Quando o pendente se resolve. Nulo nos que já nasceram resolvidos.
    ADD COLUMN settles_at      timestamptz,
    ADD COLUMN settled_at      timestamptz;

-- Os estornos de antes concluíram no momento em que foram criados.
UPDATE refund SET settled_at = created_at WHERE status = 'SUCCEEDED';

-- A varredura só olha os pendentes.
CREATE INDEX refund_pending_due ON refund (settles_at) WHERE status = 'PENDING';
