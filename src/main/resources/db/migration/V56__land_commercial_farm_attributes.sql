-- Atributos específicos por tipo de imóvel (aditivo, tudo nullable — sem impacto no existente).
-- #2 Terreno: classificação do solo/uso.
-- #6 Comercial: pé-direito (altura). Quinta/Herdade: fonte de água, uso agrícola, licença AL.

-- AL e licença de utilização já existem (al_registration_number / licenca_utilizacao) — não duplicar.
ALTER TABLE "properia"."listings"
    ADD COLUMN IF NOT EXISTS "land_type"        text,            -- urbano | urbanizavel | rustico | agricola
    ADD COLUMN IF NOT EXISTS "ceiling_height_m" numeric(4, 2),   -- pé-direito em metros (comercial/loja/escritório/armazém)
    ADD COLUMN IF NOT EXISTS "water_source"     text,            -- rede | furo | poco | none (quinta/herdade/terreno)
    ADD COLUMN IF NOT EXISTS "agricultural_use" boolean;         -- uso agrícola declarado (quinta/herdade/terreno rústico)

-- Índice parcial para acelerar o filtro de terreno por classe de solo.
CREATE INDEX IF NOT EXISTS "listings_land_type_idx"
    ON "properia"."listings" ("land_type")
    WHERE "land_type" IS NOT NULL;
