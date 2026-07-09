-- ─── Migration 0060: Retenção de documentos assinados (RGPD — limitação de conservação) ──
-- retention_until: até quando o documento assinado é conservado. Definido no momento
-- da assinatura (por defeito 5 anos — prazo prudente para prova em mediação imobiliária,
-- alinhado com o prazo ordinário de prescrição). Um processo de limpeza pode depois
-- eliminar/anonimizar registos além desta data, satisfazendo o princípio da limitação
-- da conservação (art. 5.º/1/e do RGPD).

ALTER TABLE "properia"."document_signatures"
  ADD COLUMN IF NOT EXISTS "retention_until" timestamptz;

CREATE INDEX IF NOT EXISTS "idx_docsig_retention"
  ON "properia"."document_signatures" ("retention_until");
