-- ─── Migration 0062: Modelos de contrato próprios da agência (PDF prenchível) ──
-- A agência carrega o SEU modelo de contrato (PDF com campos AcroForm, redigido pelo
-- jurista dela). A plataforma deteta os campos e preenche-os com os dados do formulário
-- (determinístico) — NUNCA gera texto jurídico. Depois corre a cerimónia de assinatura.

CREATE TABLE IF NOT EXISTS "properia"."document_templates" (
  "id"             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "advertiser_id"  uuid NOT NULL,
  "name"           text NOT NULL,
  "document_type"  text NOT NULL DEFAULT 'custom',   -- cpcv | cmi | arrendamento | custom
  "pdf_template"   bytea NOT NULL,
  "fields"         jsonb NOT NULL DEFAULT '[]'::jsonb, -- [{name, type}] campos AcroForm detetados
  "created_at"     timestamptz NOT NULL DEFAULT now(),
  "updated_at"     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "idx_doctemplate_advertiser"
  ON "properia"."document_templates" ("advertiser_id", "created_at" DESC);
