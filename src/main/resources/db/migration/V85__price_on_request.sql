-- ─────────────────────────────────────────────────────────────────────────────
-- Preço sob consulta.
--
-- Há inventário que não se anuncia com valor: prédios vendidos por negociação,
-- terrenos com potencial construtivo, ativos institucionais. Hoje o anúncio
-- simplesmente não podia existir — publicar exige preço, e sem ele o imóvel nem
-- sequer aparecia nas listagens públicas.
--
-- Porquê uma coluna e não apenas `price_amount IS NULL`: são estados diferentes.
-- Um preço em falta é um anúncio incompleto, que deve continuar a ser bloqueado
-- na publicação. "Sob consulta" é uma decisão comercial deliberada, e só ela
-- dispensa o preço. Sem a distinção, qualquer esquecimento passaria a válido.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE "properia"."listings"
    ADD COLUMN IF NOT EXISTS "price_on_request" boolean NOT NULL DEFAULT false;

-- Um anúncio sob consulta não deve ter preço: os dois juntos são contraditórios
-- e deixariam a UI sem saber qual mostrar.
ALTER TABLE "properia"."listings"
    DROP CONSTRAINT IF EXISTS "listings_price_on_request_has_no_amount";
ALTER TABLE "properia"."listings"
    ADD CONSTRAINT "listings_price_on_request_has_no_amount"
    CHECK (NOT "price_on_request" OR "price_amount" IS NULL);

-- Ordenar por preço tem de conseguir separar rapidamente quem tem valor.
CREATE INDEX IF NOT EXISTS "idx_listings_price_on_request"
    ON "properia"."listings" ("price_on_request")
    WHERE "price_on_request" = true;

COMMENT ON COLUMN "properia"."listings"."price_on_request" IS
    'Decisão deliberada de não anunciar valor. Distingue-se de preço em falta, que continua a bloquear a publicação.';
