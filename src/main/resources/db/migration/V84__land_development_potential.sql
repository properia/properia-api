-- ─────────────────────────────────────────────────────────────────────────────
-- Terrenos e prédios com potencial construtivo: frente e viabilidade.
--
-- Quem compra para construir decide por dois dados que não existiam:
--
--   frente do terreno — metros de confrontação com a via pública. Determina
--   quantos fogos ou moradias cabem, os acessos e o próprio desenho do projeto.
--   Dois terrenos com a mesma área e frentes diferentes valem valores diferentes.
--
--   potencial construtivo — o que o PDM/viabilidade permite ali: habitação
--   coletiva, moradias unifamiliares, número de pisos. É a tese de investimento.
--
-- Sem estes campos, uma ficha real ("1.714 m², ~38 m de frente, viabilidade para
-- habitação coletiva ou moradias") só cabia em texto livre na descrição, onde
-- não é filtrável nem comparável entre terrenos.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE "properia"."listings"
    ADD COLUMN IF NOT EXISTS "land_frontage_m"     numeric(8, 2),
    ADD COLUMN IF NOT EXISTS "buildable_potential" text;

-- Uma frente de zero metros significaria um terreno sem acesso à via — não é
-- ausência de dados, é impossível. A ausência representa-se com NULL.
ALTER TABLE "properia"."listings"
    DROP CONSTRAINT IF EXISTS "listings_land_frontage_positive";
ALTER TABLE "properia"."listings"
    ADD CONSTRAINT "listings_land_frontage_positive"
    CHECK ("land_frontage_m" IS NULL OR "land_frontage_m" > 0);

COMMENT ON COLUMN "properia"."listings"."land_frontage_m" IS
    'Metros de confrontação do terreno com a via pública.';
COMMENT ON COLUMN "properia"."listings"."buildable_potential" IS
    'O que a viabilidade/PDM permite construir no local.';
