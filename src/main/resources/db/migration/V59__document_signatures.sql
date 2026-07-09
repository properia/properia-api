-- ─── Migration 0059: Assinatura digital de documentos (SES + trilho de auditoria) ──
-- Assinatura Eletrónica Simples com prova forte: OTP por email + hash SHA-256 do PDF +
-- IP/user-agent/timestamps no trilho de auditoria. Os PDFs ficam guardados como bytea
-- (privados — nunca num CDN público), servidos por endpoints com controlo de acesso.

CREATE TABLE IF NOT EXISTS "properia"."document_signatures" (
  "id"                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "advertiser_id"       uuid NOT NULL,
  "created_by_user_id"  uuid,
  "lead_id"             uuid,
  "visit_id"            uuid,
  "document_type"       text NOT NULL DEFAULT 'visit_form',
  "title"               text NOT NULL,
  "status"              text NOT NULL DEFAULT 'draft',  -- draft|sent|viewed|signed|declined|expired
  "payload"             jsonb NOT NULL DEFAULT '{}'::jsonb,
  "unsigned_pdf"        bytea,
  "signed_pdf"          bytea,
  "document_hash"       text,                            -- SHA-256 (hex) do PDF assinado
  "sign_token"          text NOT NULL UNIQUE,            -- token público não adivinhável
  "otp_code_hash"       text,                            -- SHA-256 do código OTP
  "otp_expires_at"      timestamptz,
  "otp_attempts"        int NOT NULL DEFAULT 0,
  "signer_name"         text NOT NULL,
  "signer_email"        text NOT NULL,
  "signed_at"           timestamptz,
  "signer_ip"           text,
  "signer_user_agent"   text,
  "audit"               jsonb NOT NULL DEFAULT '[]'::jsonb,  -- eventos: created/sent/viewed/otp_sent/signed
  "expires_at"          timestamptz NOT NULL DEFAULT (now() + interval '30 days'),
  "created_at"          timestamptz NOT NULL DEFAULT now(),
  "updated_at"          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "idx_docsig_advertiser"
  ON "properia"."document_signatures" ("advertiser_id", "created_at" DESC);
CREATE INDEX IF NOT EXISTS "idx_docsig_token"
  ON "properia"."document_signatures" ("sign_token");
CREATE INDEX IF NOT EXISTS "idx_docsig_hash"
  ON "properia"."document_signatures" ("document_hash");
