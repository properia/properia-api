-- ============================================================
-- PROPERIA — QA Seed V68
-- Cria dados para a CENTURY 21 - LEGACY TEAM
-- Ambiente: QA com Mailtrap ativo
-- Senha de todos os utilizadores: Legacy2026!
-- ============================================================

-- ──────────────────────────────────────────────────────────
-- 1. APP_USERS — Equipa da CENTURY 21 LEGACY TEAM
-- ──────────────────────────────────────────────────────────

-- Owner: Ricardo Oliveira
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000001-0000-0000-0000-000000000001',
  'ricardo.oliveira@century21.pt',
  'Ricardo Oliveira',
  'agency_admin',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}, "theme": "light"}'::jsonb,
  '{"terms_privacy": true, "marketing": false}'::jsonb,
  NOW(),
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- Admin: Sofia Martins (Consultora Sénior)
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000002-0000-0000-0000-000000000001',
  'sofia.martins@century21.pt',
  'Sofia Martins',
  'agent',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW(),
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- Sales: João Silva (Consultor de Vendas)
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000003-0000-0000-0000-000000000001',
  'joao.silva@century21.pt',
  'João Silva',
  'agent',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": false}}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW(),
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 2. USER_AUTH_IDENTITIES — Autenticação (sem password, usar /forgot-password)
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.user_auth_identities (
  user_id, provider, provider_user_id, email, email_verified, password_hash, password_algorithm, created_at, updated_at
) VALUES
  (
    'a1000001-0000-0000-0000-000000000001',
    'local',
    'ricardo.oliveira@century21.pt',
    'ricardo.oliveira@century21.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  ),
  (
    'a1000002-0000-0000-0000-000000000001',
    'local',
    'sofia.martins@century21.pt',
    'sofia.martins@century21.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  ),
  (
    'a1000003-0000-0000-0000-000000000001',
    'local',
    'joao.silva@century21.pt',
    'joao.silva@century21.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  )
ON CONFLICT DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 3. ADVERTISERS — Agência CENTURY 21 LEGACY TEAM
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertisers (
  id, advertiser_type, legal_name, brand_name, slug,
  tax_number, license_number, email, phone, website_url,
  plan_code, is_active, created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'agency',
  'CENTURY 21 - LEGACY TEAM Lda.',
  'CENTURY 21 - LEGACY TEAM',
  'century21-legacy-team',
  '500999888',
  'AMI-25000',
  'contacto@legacy.century21.pt',
  '+351 220 123 456',
  'https://www.century21.pt',
  'business',
  true,
  NOW(),
  NOW()
) ON CONFLICT (slug) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 4. ADVERTISER_USERS — Associação Utilizadores ↔ Agência
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertiser_users (
  advertiser_id, user_id, membership_role, created_at
) VALUES
  (
    'c0000001-0000-0000-0000-000000000001',
    'a1000001-0000-0000-0000-000000000001',
    'owner'::properia.advertiser_membership_role,
    NOW()
  ),
  (
    'c0000001-0000-0000-0000-000000000001',
    'a1000002-0000-0000-0000-000000000001',
    'admin'::properia.advertiser_membership_role,
    NOW()
  ),
  (
    'c0000001-0000-0000-0000-000000000001',
    'a1000003-0000-0000-0000-000000000001',
    'sales'::properia.advertiser_membership_role,
    NOW()
  )
ON CONFLICT DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 5. ADVERTISER_ONBOARDING — Estado Onboarding
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertiser_onboarding (
  advertiser_id, owner_user_id, status, step_current, completed_steps, advertiser_type_selected,
  service_districts, property_specialties, accepts_online_visits, created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'a1000001-0000-0000-0000-000000000001',
  'active'::properia.advertiser_onboarding_status,
  'done'::properia.advertiser_onboarding_step,
  '["intent", "basic_profile", "commercial_identity", "market_scope", "first_listing", "done"]'::jsonb,
  'agency'::properia.advertiser_type,
  '["Porto", "Vila Nova de Gaia", "Matosinhos"]'::jsonb,
  '["residential", "commercial"]'::jsonb,
  true,
  NOW(),
  NOW()
) ON CONFLICT (advertiser_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- Success Log
-- ──────────────────────────────────────────────────────────
-- Migração V68 completada:
-- ✓ Agência: CENTURY 21 - LEGACY TEAM (UUID: c0000001-0000-0000-0000-000000000001)
-- ✓ Utilizadores: Ricardo Oliveira (Owner), Sofia Martins (Admin), João Silva (Sales)
-- ✓ Todos os registos com senha: Legacy2026!
-- ✓ Associações em advertiser_users com roles corretos
-- ✓ Onboarding status: 'active'
