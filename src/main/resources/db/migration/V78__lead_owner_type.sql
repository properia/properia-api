-- ─────────────────────────────────────────────────────────────────────────────
-- V78 — Leads de angariação (proprietário / vendedor)
--
-- Até aqui a tabela `leads` só sabia representar procura: `listing_id` era NOT
-- NULL e o `advertiser_id` era derivado do imóvel (CreateLeadUseCase). Um lead
-- de angariação é o inverso — o imóvel ainda não existe, é o que se vai
-- angariar. Sem estas alterações não há forma de o representar.
--
-- NOTA sobre `ALTER TYPE ... ADD VALUE` (abaixo): em PostgreSQL < 12 não corria
-- dentro de transação, e em 12+ não pode partilhar transação com o USO do valor
-- novo. Nenhum dos casos se aplica aqui — os valores adicionados a `lead_source`
-- não são usados neste ficheiro, e `lead_type` é criado nesta mesma transação
-- (o que o Postgres permite). Verificado contra PostgreSQL 16, a versão usada em
-- testes e em produção: esta migração comita de forma atómica.
--
-- Ou seja: NÃO é preciso executeInTransaction=false. Se for preciso partir esta
-- migração no futuro, o que a obrigaria a isso seria passar a usar
-- 'owner_landing'/'owner_referral' num UPDATE dentro do próprio ficheiro.
--
-- As instruções são ainda assim idempotentes, por consistência com o resto das
-- migrações do projeto.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Novo tipo: distingue o lado do mercado a que o lead pertence.
DO $$ BEGIN
  CREATE TYPE "properia"."lead_type" AS ENUM('buyer', 'owner');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 2. Novas origens de captação (landing pública de angariação e referências).
ALTER TYPE "properia"."lead_source" ADD VALUE IF NOT EXISTS 'owner_landing';
ALTER TYPE "properia"."lead_source" ADD VALUE IF NOT EXISTS 'owner_referral';

-- 3. Colunas novas.
--    contact_verified: o formulário é público e anónimo. Sem verificação por
--    código, o CRM enche-se de emails falsos e a equipa comercial perde a
--    confiança na fonte — o que mata o canal mais depressa do que a falta dele.
ALTER TABLE "properia"."leads"
  ADD COLUMN IF NOT EXISTS "lead_type" "properia"."lead_type" DEFAULT 'buyer' NOT NULL,
  ADD COLUMN IF NOT EXISTS "contact_verified" boolean DEFAULT false NOT NULL;

-- 4. Um lead de proprietário não tem imóvel (ainda).
ALTER TABLE "properia"."leads" ALTER COLUMN "listing_id" DROP NOT NULL;

-- 5. ... e pode também não ter anunciante, enquanto não houver regra de
--    encaminhamento configurada para a zona (fila por encaminhar). Nenhuma
--    query de CRM existente é afetada: todas filtram `advertiser_id = :adv`,
--    e NULL nunca corresponde.
ALTER TABLE "properia"."leads" ALTER COLUMN "advertiser_id" DROP NOT NULL;

-- 6. As garantias que se perderam acima são repostas para o lado do comprador,
--    onde continuam a ser invariantes.
DO $$ BEGIN
  ALTER TABLE "properia"."leads"
    ADD CONSTRAINT "leads_buyer_requires_listing"
    CHECK ("lead_type" <> 'buyer' OR "listing_id" IS NOT NULL);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  ALTER TABLE "properia"."leads"
    ADD CONSTRAINT "leads_buyer_requires_advertiser"
    CHECK ("lead_type" <> 'buyer' OR "advertiser_id" IS NOT NULL);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 7. Índice do pipeline de angariação (listagem do CRM: anunciante + tipo +
--    etapa, ordenado por entrada).
CREATE INDEX IF NOT EXISTS "idx_leads_owner_pipeline"
  ON "properia"."leads" USING btree ("advertiser_id", "lead_type", "stage", "created_at" DESC);

-- 8. Deduplicação de leads de proprietário. O dedup existente é por
--    (listing_id, user_id) e não se aplica: o proprietário é anónimo e não há
--    imóvel. A chave prática passa a ser o email.
CREATE INDEX IF NOT EXISTS "idx_leads_owner_dedup"
  ON "properia"."leads" (lower("contact_email"), "created_at" DESC)
  WHERE "lead_type" = 'owner';
