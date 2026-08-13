-- ─────────────────────────────────────────────────────────────────────────────
-- Prédios de rendimento: frações, renda e estado documental.
--
-- Um prédio vendido como investimento não se descreve com os campos de uma
-- habitação. O que decide a compra é: quantas frações tem, quanto rende por mês,
-- em que estado está cada uma e que contratos as ocupam. Nada disso era
-- exprimível — uma ficha real ("Prédio, 5 habitações, 1.334,70 €/mês, duas com
-- contrato vitalício") só podia ser despejada em texto livre na descrição, onde
-- não é filtrável, não é comparável e não aparece em lado nenhum da listagem.
--
-- `units_breakdown` é jsonb e não uma tabela filha de propósito: é informação de
-- APRESENTAÇÃO, lida sempre em bloco com o anúncio e nunca consultada por si.
-- Uma tabela filha traria joins a todas as leituras para nada.
--
-- Forma de cada entrada (validada no cliente, ver shared/contracts/listings.ts):
--   { "label": "Unidade 1", "typology": "T1", "condition": "remodelada",
--     "contractType": "annual" | "lifetime" | "none", "monthlyRent": 500.00 }
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE "properia"."listings"
    ADD COLUMN IF NOT EXISTS "total_units"          integer,
    ADD COLUMN IF NOT EXISTS "monthly_income_total" numeric(12, 2),
    ADD COLUMN IF NOT EXISTS "units_breakdown"      jsonb,
    ADD COLUMN IF NOT EXISTS "legal_status_note"    text;

-- Um prédio não tem "zero" frações; a ausência de informação é NULL, não 0.
ALTER TABLE "properia"."listings"
    DROP CONSTRAINT IF EXISTS "listings_total_units_positive";
ALTER TABLE "properia"."listings"
    ADD CONSTRAINT "listings_total_units_positive"
    CHECK ("total_units" IS NULL OR "total_units" > 0);

ALTER TABLE "properia"."listings"
    DROP CONSTRAINT IF EXISTS "listings_monthly_income_non_negative";
ALTER TABLE "properia"."listings"
    ADD CONSTRAINT "listings_monthly_income_non_negative"
    CHECK ("monthly_income_total" IS NULL OR "monthly_income_total" >= 0);

-- Índice parcial: só os imóveis com rendimento declarado interessam para
-- ordenar/filtrar por rentabilidade, e são uma minoria do inventário.
CREATE INDEX IF NOT EXISTS "idx_listings_monthly_income"
    ON "properia"."listings" ("monthly_income_total")
    WHERE "monthly_income_total" IS NOT NULL;

COMMENT ON COLUMN "properia"."listings"."total_units" IS
    'Número de frações/habitações independentes. Só faz sentido em prédios e similares.';
COMMENT ON COLUMN "properia"."listings"."monthly_income_total" IS
    'Renda mensal total gerada pelas frações ocupadas, em euros.';
COMMENT ON COLUMN "properia"."listings"."legal_status_note" IS
    'Situação documental relevante para a compra (licença de utilização, propriedade horizontal).';
