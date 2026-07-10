-- ─── Migration 0061: Assinatura multi-parte ──────────────────────────────────
-- Um documento (document_signatures = "envelope") pode ter vários signatários — ex.:
-- CPCV assinado pelo promitente-vendedor E pelo promitente-comprador. Cada signatário
-- tem o seu próprio link (token), OTP e prova. O PDF final só é selado quando TODOS
-- assinam. A ficha de visita e o CMI usam o mesmo modelo com 1 signatário.

CREATE TABLE IF NOT EXISTS "properia"."document_signers" (
  "id"                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "document_id"         uuid NOT NULL REFERENCES "properia"."document_signatures"("id") ON DELETE CASCADE,
  "role"                text NOT NULL,                    -- client | seller | buyer
  "role_label"          text NOT NULL,                    -- rótulo apresentado (ex.: "Promitente-comprador")
  "sign_order"          int NOT NULL DEFAULT 1,
  "signer_name"         text NOT NULL,
  "signer_email"        text NOT NULL,
  "sign_token"          text NOT NULL UNIQUE,
  "otp_code_hash"       text,
  "otp_expires_at"      timestamptz,
  "otp_attempts"        int NOT NULL DEFAULT 0,
  "status"              text NOT NULL DEFAULT 'pending',  -- pending | viewed | signed
  "signed_at"           timestamptz,
  "signer_ip"           text,
  "signer_user_agent"   text,
  "signature_image"     bytea,
  "created_at"          timestamptz NOT NULL DEFAULT now(),
  "updated_at"          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS "idx_docsigner_document"
  ON "properia"."document_signers" ("document_id", "sign_order");
CREATE INDEX IF NOT EXISTS "idx_docsigner_token"
  ON "properia"."document_signers" ("sign_token");

-- 'partially_signed' passa a ser um estado válido do envelope (alguns assinaram, faltam outros).
-- (status é text livre em document_signatures, não é preciso alterar tipo.)
