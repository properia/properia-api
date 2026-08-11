-- ============================================================
-- PROPERIA — V76: Condições especiais de aquisição
--
-- Identifica imóveis cujo preço anunciado NÃO corresponde à compra plena do
-- bem (nua propriedade, quota parte, exploração turística). Estes poluem as
-- listagens: um T4 em Cascais a 180.000 € parece uma pechincha e domina
-- ordenações por preço/m² quando na verdade não dá posse nem uso imediato.
--
-- TEXT + CHECK em vez de ENUM Postgres: esta base já custou vários incidentes
-- de produção por casts de enum em falta (lead_source, buyer_consent_status,
-- advertiser_membership_role). Com TEXT o JdbcClient compara sem cast, o
-- CHECK garante o mesmo domínio fechado, e acrescentar um valor novo é um
-- ALTER simples em vez de ALTER TYPE (que não corre dentro de transação).
-- ============================================================

ALTER TABLE "properia"."listings"
  ADD COLUMN IF NOT EXISTS "ownership_type" text NOT NULL DEFAULT 'FULL',
  ADD COLUMN IF NOT EXISTS "usage_restriction" text NOT NULL DEFAULT 'NONE',
  ADD COLUMN IF NOT EXISTS "special_condition_summary" text;

ALTER TABLE "properia"."listings"
  DROP CONSTRAINT IF EXISTS "listings_ownership_type_check";
ALTER TABLE "properia"."listings"
  ADD CONSTRAINT "listings_ownership_type_check"
  CHECK ("ownership_type" IN ('FULL', 'NUDE_OWNERSHIP', 'PARTIAL_SHARE'));

ALTER TABLE "properia"."listings"
  DROP CONSTRAINT IF EXISTS "listings_usage_restriction_check";
ALTER TABLE "properia"."listings"
  ADD CONSTRAINT "listings_usage_restriction_check"
  CHECK ("usage_restriction" IN ('NONE', 'TENANT_VITALICIO', 'TOURISTIC_EXPLORATION'));

-- Coluna gerada, não mantida por código: is_special_condition é por definição
-- uma função das outras duas. Se fosse a aplicação a escrever, bastaria um
-- caminho de escrita esquecido (importação, patch, seed) para o imóvel deixar
-- de ser filtrado — falhando exatamente no sentido perigoso, mostrá-lo como
-- se fosse uma venda normal. Assim é impossível dessincronizar.
ALTER TABLE "properia"."listings"
  ADD COLUMN IF NOT EXISTS "is_special_condition" boolean
  GENERATED ALWAYS AS ("ownership_type" <> 'FULL' OR "usage_restriction" <> 'NONE') STORED;

-- Índice parcial: a pesquisa por omissão pede is_special_condition = FALSE, e
-- os especiais são uma minoria — indexar só as linhas TRUE mantém o índice
-- pequeno e serve tanto a exclusão como a listagem dedicada.
CREATE INDEX IF NOT EXISTS "listings_special_condition_idx"
  ON "properia"."listings" ("is_special_condition")
  WHERE "is_special_condition" = true;
