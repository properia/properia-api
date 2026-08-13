-- ─────────────────────────────────────────────────────────────────────────────
-- Tabela em falta: vocação do espaço (utilização sugerida para imóveis comerciais).
--
-- O AdvertiserMiscController consulta `properia.listing_space_vocations` desde
-- que o passo "Utilização do espaço" existe no wizard, mas NENHUMA migração
-- alguma vez a criou — confirmado por `git log -S` em todo o histórico. O
-- endpoint respondia sempre 500 com:
--
--     relation "properia.listing_space_vocations" does not exist
--
-- Passou despercebido porque o passo só aparece em tipos comerciais, e até hoje
-- não tinha sido registado nenhum. Apareceu ao cadastrar um prédio: `building`
-- é um tipo comercial, o wizard mostrou o passo, e a chamada falhou.
--
-- As colunas seguem exatamente o que loadVocacao() lê.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS "properia"."listing_space_vocations" (
    "listing_id"         uuid PRIMARY KEY
                         REFERENCES "properia"."listings"("id") ON DELETE CASCADE,
    "primary_use"        text,
    -- Lista de usos alternativos. jsonb porque é lida sempre em bloco com a linha.
    "secondary_uses"     jsonb NOT NULL DEFAULT '[]'::jsonb,
    "adaptation_level"   text,
    "open_to_remodeling" boolean NOT NULL DEFAULT false,
    -- 0–1: confiança da sugestão automática. NULL quando foi o anunciante a decidir.
    "confidence_score"   numeric(4, 3),
    -- Preenchido quando o anunciante confirma a sugestão; distingue "sugerido"
    -- de "confirmado por uma pessoa", que é o que a UI mostra ao comprador.
    "confirmed_at"       timestamptz,
    "confirmed_by"       uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL,
    "created_at"         timestamptz NOT NULL DEFAULT now(),
    "updated_at"         timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE "properia"."listing_space_vocations"
    DROP CONSTRAINT IF EXISTS "listing_space_vocations_confidence_range";
ALTER TABLE "properia"."listing_space_vocations"
    ADD CONSTRAINT "listing_space_vocations_confidence_range"
    CHECK ("confidence_score" IS NULL
           OR ("confidence_score" >= 0 AND "confidence_score" <= 1));

CREATE INDEX IF NOT EXISTS "idx_listing_space_vocations_confirmed"
    ON "properia"."listing_space_vocations" ("confirmed_at")
    WHERE "confirmed_at" IS NOT NULL;
