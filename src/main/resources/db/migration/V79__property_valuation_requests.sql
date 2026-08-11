-- ─────────────────────────────────────────────────────────────────────────────
-- V79 — Pedidos de avaliação submetidos pela landing pública de angariação
--
-- Snapshot imutável do que o proprietário submeteu + a estimativa que lhe foi
-- mostrada. Deliberadamente separado de `listings`: um formulário público dá 8
-- campos, `listings` tem ~70 e um validador de prontidão para publicação. Criar
-- um imóvel meio vazio por cada submissão inflacionaria contadores, consumiria
-- quota de plano com leads frios e encheria as listagens do anunciante de
-- fantasmas. O imóvel só nasce quando o consultor qualifica (promote-to-listing).
--
-- `estimate_inputs` guarda as ENTRADAS do cálculo, não só o resultado: quando um
-- proprietário contestar o valor daqui a seis meses, tem de ser possível
-- reconstruir exatamente como se lá chegou. Sem isto não há defesa possível.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS "properia"."property_valuation_requests" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "lead_id" uuid NOT NULL,
  -- Token opaco para o link do relatório (/vender/relatorio/{token}). Nunca
  -- expor o id interno num link enviado por email.
  "public_token" text NOT NULL,

  -- ── Snapshot do imóvel ────────────────────────────────────────────────────
  "address_raw" text,
  "postal_code" text,
  "district" text,
  "municipality" text,
  "parish" text,
  "latitude" double precision,
  "longitude" double precision,
  "property_type" "properia"."property_type" NOT NULL,
  "bedrooms" integer,
  "usable_area_m2" numeric(10, 2),
  "condition_status" "properia"."condition_status",
  "floor_number" integer,
  "has_elevator" boolean,
  "energy_rating" text,

  -- ── Qualificação comercial ────────────────────────────────────────────────
  -- selling_horizon é o sinal de prioridade nº1 do pipeline de angariação.
  "selling_horizon" text,
  "has_agency" boolean,
  "motivation" text,

  -- ── Estimativa apresentada ────────────────────────────────────────────────
  "estimate_min" numeric(12, 2),
  "estimate_max" numeric(12, 2),
  "estimate_ppm2" numeric(10, 2),
  "estimate_confidence" text,
  "estimate_sample_size" integer DEFAULT 0 NOT NULL,
  "estimate_source" text,
  "estimate_inputs" jsonb DEFAULT '{}'::jsonb NOT NULL,

  -- ── Consentimento RGPD ────────────────────────────────────────────────────
  -- O texto é guardado por extenso (e não uma referência a uma versão) porque a
  -- prova de consentimento tem de ser exatamente o que a pessoa leu no ecrã.
  "consent_granted" boolean DEFAULT false NOT NULL,
  "consent_text" text NOT NULL,
  "consent_ip" inet,
  "consent_at" timestamp with time zone,
  "marketing_consent" boolean DEFAULT false NOT NULL,

  -- ── Verificação de contacto (OTP por email) ───────────────────────────────
  -- O proprietário é anónimo (não tem conta), por isso o código vive aqui e não
  -- em visit_email_verifications, que está ancorada a app_users.
  "contact_code_hash" text,
  "contact_code_expires_at" timestamp with time zone,
  "contact_code_last_sent_at" timestamp with time zone,
  "contact_code_failed_attempts" integer DEFAULT 0 NOT NULL,
  "contact_verified_at" timestamp with time zone,

  -- ── Proveniência ──────────────────────────────────────────────────────────
  "utm" jsonb DEFAULT '{}'::jsonb NOT NULL,

  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,

  CONSTRAINT "property_valuation_requests_public_token_unique" UNIQUE ("public_token"),
  CONSTRAINT "property_valuation_requests_confidence_check"
    CHECK ("estimate_confidence" IS NULL OR "estimate_confidence" IN ('low', 'medium', 'high')),
  CONSTRAINT "property_valuation_requests_source_check"
    CHECK ("estimate_source" IS NULL OR "estimate_source" IN (
      'listings_parish', 'listings_municipality', 'market_benchmark', 'none')),
  CONSTRAINT "property_valuation_requests_horizon_check"
    CHECK ("selling_horizon" IS NULL OR "selling_horizon" IN (
      'immediate', '3m', '6m', 'exploring')),
  -- Um intervalo invertido significaria um bug no motor a chegar ao proprietário.
  CONSTRAINT "property_valuation_requests_range_check"
    CHECK ("estimate_min" IS NULL OR "estimate_max" IS NULL OR "estimate_min" <= "estimate_max")
);

DO $$ BEGIN
  ALTER TABLE "properia"."property_valuation_requests"
  ADD CONSTRAINT "property_valuation_requests_lead_id_leads_id_fk"
  FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE cascade ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS "idx_pvr_lead"
  ON "properia"."property_valuation_requests" USING btree ("lead_id");

CREATE INDEX IF NOT EXISTS "idx_pvr_created"
  ON "properia"."property_valuation_requests" USING btree ("created_at" DESC);

-- Deduplicação/antifraude: mesma morada submetida repetidamente em pouco tempo.
CREATE INDEX IF NOT EXISTS "idx_pvr_location"
  ON "properia"."property_valuation_requests" USING btree ("municipality", "parish", "created_at" DESC);
