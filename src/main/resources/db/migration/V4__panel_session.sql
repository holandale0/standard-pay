-- Sessões do painel web do lojista.
--
-- O lojista entra com a chave de API, uma vez, e recebe um cookie. O cookie
-- leva um token aleatório; aqui fica só o hash dele, como na chave de API:
-- quem lê o banco não consegue entrar no painel de ninguém.

CREATE TABLE panel_session (
    token_hash   char(64)     PRIMARY KEY,
    merchant_id  varchar(40)  NOT NULL REFERENCES merchant (id),
    -- Vai escondido em cada formulário do painel; um POST sem ele é recusado.
    csrf_token   varchar(40)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL
);

CREATE INDEX panel_session_expiry ON panel_session (expires_at);
