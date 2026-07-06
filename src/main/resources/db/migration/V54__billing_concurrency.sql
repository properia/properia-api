-- Concorrência no billing: torna webhooks Stripe idempotentes e protege créditos de
-- duplicação. Ver correções #1/#2/#3 (patchBillingMetadata atómico, grantWelcomeCredits,
-- dedup de webhooks). As mutações de metadata passam a usar `||` (jsonb concat) atómico
-- ao nível da linha, pelo que não é preciso schema novo para isso — só para a dedup.

-- Registo de eventos Stripe já processados (entrega at-least-once → replays/duplicados).
CREATE TABLE IF NOT EXISTS "properia"."stripe_webhook_events" (
    "event_id"     text PRIMARY KEY,
    "event_type"   text,
    "processed_at" timestamptz NOT NULL DEFAULT now()
);

-- Idempotência de créditos comprados via Stripe: uma sessão de checkout só pode gerar
-- uma transação de crédito, mesmo que o webhook seja reentregue.
CREATE UNIQUE INDEX IF NOT EXISTS "advertiser_credit_tx_stripe_session_unique"
    ON "properia"."advertiser_credit_transactions" ("stripe_checkout_session_id")
    WHERE "stripe_checkout_session_id" IS NOT NULL;
