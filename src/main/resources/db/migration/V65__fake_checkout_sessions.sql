-- Sessões de checkout "fake" (modo dev, properia.stripe.billing-provider=fake) que ainda não
-- têm um Payment Intent/Session real da Stripe para dar idempotência ao webhook. Guardamos a
-- intenção no momento da criação do checkout e resgatamo-la (claim atómico, uma só vez) quando
-- o frontend volta do "redirect" fake — mesmo padrão de outras colunas billing_metadata deste
-- módulo (claim em WHERE, sem check-then-act).
CREATE TABLE properia.fake_checkout_sessions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    advertiser_id uuid NOT NULL REFERENCES properia.advertisers(id) ON DELETE CASCADE,
    pack_code     varchar(32) NOT NULL,
    credits       int NOT NULL CHECK (credits > 0),
    consumed_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_fake_checkout_sessions_advertiser ON properia.fake_checkout_sessions(advertiser_id);
