-- ─────────────────────────────────────────────────────────────────────────────
-- Onboarding de um anunciante novo, com plano permanente e acesso por email.
--
-- USO:
--   psql "$DATABASE_URL" \
--     -v adv_name="Ricardo Martins Imobiliária Lda" \
--     -v adv_brand="Ricardo Martins" \
--     -v adv_slug="ricardo-martins" \
--     -v adv_email="geral@exemplo.pt" \
--     -v owner_email="ricardo@exemplo.pt" \
--     -v owner_name="Ricardo Martins" \
--     -v plan="business" \
--     -f scripts/onboard-advertiser.sql
--
-- Depois de correr, envie o email de definição de password:
--   curl -X POST https://properia-api.onrender.com/api/auth/password/forgot \
--        -H 'Content-Type: application/json' \
--        -d '{"email":"ricardo@exemplo.pt"}'
--
-- Idempotente: pode correr várias vezes sem duplicar nada.
-- ─────────────────────────────────────────────────────────────────────────────

\set ON_ERROR_STOP on

BEGIN;

-- 1. ANUNCIANTE ───────────────────────────────────────────────────────────────
-- `plan_code` é o que decide o acesso (ver AdvertiserBillingRepository e
-- shared/advertiser-plan-access.ts). Não existe tabela de subscrições nem data
-- de fim: um plano escrito aqui é permanente até alguém o mudar. É assim que se
-- consegue um "business vitalício" — e é também por isso que NÃO se escreve
-- nada de trial em billing_metadata, senão a UI passa a mostrar contagem
-- decrescente e a tratá-lo como período experimental.
INSERT INTO properia.advertisers (advertiser_type, legal_name, brand_name, slug, email, plan_code, is_active, billing_metadata)
VALUES ('agency', :'adv_name', :'adv_brand', :'adv_slug', :'adv_email', :'plan', true, '{}'::jsonb)
ON CONFLICT (slug) DO UPDATE
  SET legal_name = EXCLUDED.legal_name,
      brand_name = EXCLUDED.brand_name,
      email      = EXCLUDED.email,
      plan_code  = EXCLUDED.plan_code,
      is_active  = true,
      updated_at = now();

-- 2. UTILIZADOR ───────────────────────────────────────────────────────────────
INSERT INTO properia.app_users (email, full_name, role, locale, is_active, preferences, consents)
VALUES (lower(:'owner_email'), :'owner_name', 'agency_admin', 'pt-PT', true, '{}'::jsonb,
        '{"terms_privacy": true}'::jsonb)
ON CONFLICT (email) DO UPDATE
  SET full_name = EXCLUDED.full_name,
      is_active = true,
      updated_at = now();

-- 3. IDENTIDADE LOCAL ─────────────────────────────────────────────────────────
-- ⚠️ ESTE PASSO É O QUE MAIS FALHA, E FALHA EM SILÊNCIO.
-- ResetPasswordUseCase grava a password com um UPDATE:
--     UPDATE user_auth_identities SET password_hash = ...
--     WHERE user_id = :userId AND provider = 'local'
-- Sem esta linha, o UPDATE afeta 0 registos. O email de definição de password é
-- enviado, o link funciona, o formulário diz que correu bem — e a pessoa
-- continua sem conseguir entrar, sem qualquer erro visível.
--
-- password_hash fica NULL de propósito: só é preenchido quando ele define a
-- password. Ninguém, nem nós, sabe uma password intermédia.
INSERT INTO properia.user_auth_identities (user_id, provider, provider_user_id, email, email_verified, password_hash)
SELECT u.id, 'local', lower(:'owner_email'), lower(:'owner_email'), true, NULL
FROM properia.app_users u
WHERE u.email = lower(:'owner_email')
ON CONFLICT DO NOTHING;

-- 4. MEMBRO DA EQUIPA (owner) ─────────────────────────────────────────────────
INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role)
SELECT a.id, u.id, 'owner'
FROM properia.advertisers a, properia.app_users u
WHERE a.slug = :'adv_slug' AND u.email = lower(:'owner_email')
ON CONFLICT (advertiser_id, user_id) DO UPDATE
  SET membership_role = 'owner';

-- 5. ONBOARDING CONCLUÍDO ─────────────────────────────────────────────────────
-- Sem isto o CRM recebe-o com o wizard de configuração inicial em vez do painel.
INSERT INTO properia.advertiser_onboarding (advertiser_id, owner_user_id, status, step_current, advertiser_type_selected)
SELECT a.id, u.id, 'active', 'intent', 'agency'
FROM properia.advertisers a, properia.app_users u
WHERE a.slug = :'adv_slug' AND u.email = lower(:'owner_email')
ON CONFLICT (advertiser_id) DO UPDATE
  SET status = 'active', updated_at = now();

-- 6. RESUMO ───────────────────────────────────────────────────────────────────
SELECT a.brand_name AS anunciante,
       a.plan_code  AS plano,
       u.email      AS owner,
       au.membership_role AS papel,
       (SELECT count(*) FROM properia.listings l WHERE l.advertiser_id = a.id) AS imoveis
FROM properia.advertisers a
JOIN properia.advertiser_users au ON au.advertiser_id = a.id
JOIN properia.app_users u ON u.id = au.user_id
WHERE a.slug = :'adv_slug';

COMMIT;
