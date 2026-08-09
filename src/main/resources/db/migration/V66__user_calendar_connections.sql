-- Fundação da sincronização bidirecional Google Calendar <-> Properia Visitas, por CONSULTOR
-- (não por agência). A ligação existente em advertiser_calendar_connections é 1-por-agência
-- (usada hoje só para criar a sala de Google Meet) e continua intocada — esta tabela é a nova
-- camada, uma ligação por utilizador, para inserir/atualizar visitas na agenda pessoal de cada
-- consultor e para ler a disponibilidade real dele (FreeBusy) antes de mostrar slots ao comprador.
CREATE TABLE properia.user_calendar_connections (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  uuid NOT NULL REFERENCES properia.app_users(id) ON DELETE CASCADE,
    advertiser_id            uuid NOT NULL REFERENCES properia.advertisers(id) ON DELETE CASCADE,
    provider                 varchar(32) NOT NULL DEFAULT 'google_calendar',
    account_email            varchar(320),
    access_token_encrypted   text,
    refresh_token_encrypted  text,
    token_expires_at         timestamptz,
    scopes                   jsonb NOT NULL DEFAULT '[]'::jsonb,
    status                   varchar(16) NOT NULL DEFAULT 'active'
                              CHECK (status IN ('active', 'revoked', 'error')),
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_calendar_connections_user_provider_unique UNIQUE (user_id, provider)
);

CREATE INDEX idx_user_calendar_connections_advertiser ON properia.user_calendar_connections(advertiser_id);

-- Rasto do evento espelhado na agenda pessoal do consultor — distinto de
-- external_calendar_event_id (esse é o evento da sala de Meet na agenda da AGÊNCIA).
ALTER TABLE properia.visits
    ADD COLUMN IF NOT EXISTS consultant_calendar_event_id  text,
    ADD COLUMN IF NOT EXISTS consultant_calendar_synced_at timestamptz;
