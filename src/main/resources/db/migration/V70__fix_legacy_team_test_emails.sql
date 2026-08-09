-- ============================================================
-- PROPERIA — V70: Fix CENTURY 21 LEGACY TEAM — Use Test Emails
-- Corrigir V68: usar emails fictícios de teste em vez de reais
-- ============================================================

-- Remover dados antigos de V68 (emails reais)
DELETE FROM properia.advertiser_users
WHERE advertiser_id = 'c0000001-0000-0000-0000-000000000001';

DELETE FROM properia.advertiser_onboarding
WHERE advertiser_id = 'c0000001-0000-0000-0000-000000000001';

DELETE FROM properia.user_auth_identities
WHERE user_id IN (
  'a1000001-0000-0000-0000-000000000001',
  'a1000002-0000-0000-0000-000000000001',
  'a1000003-0000-0000-0000-000000000001'
);

DELETE FROM properia.app_users
WHERE id IN (
  'a1000001-0000-0000-0000-000000000001',
  'a1000002-0000-0000-0000-000000000001',
  'a1000003-0000-0000-0000-000000000001'
);

-- ──────────────────────────────────────────────────────────
-- 1. APP_USERS — Equipa da CENTURY 21 LEGACY TEAM (TEST EMAILS)
-- ──────────────────────────────────────────────────────────

-- Owner: Ricardo Oliveira (TEST EMAIL)
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000001-0000-0000-0000-000000000001',
  'test.ricardo@properia.pt',
  'Ricardo Oliveira',
  'agency_admin',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}, "theme": "light"}'::jsonb,
  '{"terms_privacy": true, "marketing": false}'::jsonb,
  NOW(),
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- Admin: Sofia Martins (TEST EMAIL)
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000002-0000-0000-0000-000000000001',
  'test.sofia@properia.pt',
  'Sofia Martins',
  'agent',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW(),
  NOW()
) ON CONFLICT (email) DO NOTHING;

-- Sales: João Silva (TEST EMAIL)
INSERT INTO properia.app_users (
  id, email, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES (
  'a1000003-0000-0000-0000-000000000001',
  'test.joao@properia.pt',
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
    'test.ricardo@properia.pt',
    'test.ricardo@properia.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  ),
  (
    'a1000002-0000-0000-0000-000000000001',
    'local',
    'test.sofia@properia.pt',
    'test.sofia@properia.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  ),
  (
    'a1000003-0000-0000-0000-000000000001',
    'local',
    'test.joao@properia.pt',
    'test.joao@properia.pt',
    false,
    NULL,
    NULL,
    NOW(),
    NOW()
  )
ON CONFLICT DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 3. ADVERTISERS — Agência CENTURY 21 LEGACY TEAM (sem mudanças)
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
  'contact@legacy.century21.pt',
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
-- Migração V70 completada:
-- ✓ Removidas contas com emails reais (V68)
-- ✓ Criadas contas com emails fictícios de teste:
--   - test.ricardo@properia.pt (Owner)
--   - test.sofia@properia.pt (Admin)
--   - test.joao@properia.pt (Sales)
-- ✓ Todas as contas prontas para /forgot-password flow
-- ✓ Emails fictícios evitam problemas de deliverability/privacy
