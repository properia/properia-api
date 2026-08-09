-- ============================================================
-- PROPERIA — V72: Seed QA Leads, Visitas e Chat — CENTURY 21 LEGACY TEAM
-- Etapa 3 do seed de QA: usa a equipa da V68/V70 e os imóveis da V71,
-- adiciona um 4º imóvel (draft), 2 leads, 1 visita confirmada e um
-- histórico de chat comprador↔consultor, tudo idempotente.
--
-- NOTA: os "consultores atribuídos" (Sofia/João) referenciados no
-- briefing por sofia.martins@century21.pt / joao.silva@century21.pt
-- são os MESMOS utilizadores da V70 (test.sofia@properia.pt /
-- test.joao@properia.pt) — o domínio @century21.pt é só o nome
-- comercial da agência, a conta real de login usa o email de teste
-- @properia.pt para evitar problemas de deliverability (ver V70).
-- ============================================================

-- ──────────────────────────────────────────────────────────
-- 1. IMÓVEL 4 — Apartamento vista para o mar (draft) — Ricardo Oliveira
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  title, title_normalized,
  description_raw, description_normalized,
  price_amount, price_currency,
  bedrooms, bathrooms,
  city, district, parish, country_code,
  data_entry_at, created_at, updated_at
) VALUES (
  'd1000004-0000-0000-0000-000000000001',
  'qa-apto-vista-mar-century21',
  'c0000001-0000-0000-0000-000000000001',
  'a1000001-0000-0000-0000-000000000001',
  'manual', 'draft', 'sale', 'apartment',
  'Apartamento vista para o mar',
  'APARTAMENTO VISTA PARA O MAR',
  'Apartamento com vista mar, ainda em preparação para publicação.',
  'apartamento com vista mar ainda em preparacao para publicacao',
  250000.00, 'EUR',
  2, 1.0,
  'Cascais', 'Lisboa', 'Cascais e Estoril', 'PT',
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 2. BUYERS — contas mínimas para Pedro e Maria (leads com conta,
--    necessário para leads.user_id e para o chat, que exige
--    buyer_user_id NOT NULL em chat_conversations)
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.app_users (
  id, email, phone, full_name, role, locale, is_active, preferences, consents, created_at, updated_at
) VALUES
  (
    'b1000001-0000-0000-0000-000000000001',
    'pedro.alvares@gmail.com',
    '+351912345678',
    'Pedro Álvares',
    'buyer', 'pt-PT', true,
    '{"notifications": {"email": true, "push": false}}'::jsonb,
    '{"terms_privacy": true, "marketing": false}'::jsonb,
    NOW(), NOW()
  ),
  (
    'b1000002-0000-0000-0000-000000000001',
    'maria.santos@outlook.com',
    '+351967654321',
    'Maria Santos',
    'buyer', 'pt-PT', true,
    '{"notifications": {"email": true, "push": false}}'::jsonb,
    '{"terms_privacy": true, "marketing": false}'::jsonb,
    NOW(), NOW()
  )
ON CONFLICT (email) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 3. LEADS
-- ──────────────────────────────────────────────────────────

-- Lead 1: Pedro Álvares -> T3 Avenidas Novas -> Sofia Martins (admin)
INSERT INTO properia.leads (
  id, listing_id, user_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email, contact_phone,
  score, assigned_to, metadata, created_at, updated_at
) VALUES (
  'e1000001-0000-0000-0000-000000000001',
  'd1000001-0000-0000-0000-000000000001',
  'b1000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'chat', 'visit_scheduled', 'buy',
  'Boa tarde, gostaria de saber se o valor do condomínio está incluído ou qual é a quota mensal?',
  'Pedro Álvares', 'pedro.alvares@gmail.com', '+351912345678',
  80.00,
  'a1000002-0000-0000-0000-000000000001',
  '{}'::jsonb,
  NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour 40 minutes'
) ON CONFLICT (id) DO NOTHING;

-- Lead 2: Maria Santos -> Moradia T4 Cascais -> João Silva (sales)
INSERT INTO properia.leads (
  id, listing_id, user_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email, contact_phone,
  score, assigned_to, metadata, created_at, updated_at
) VALUES (
  'e1000002-0000-0000-0000-000000000001',
  'd1000002-0000-0000-0000-000000000001',
  'b1000002-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'listing_detail', 'qualified', 'buy',
  'Boa tarde, tenho interesse na moradia em Cascais. Podem enviar mais informações?',
  'Maria Santos', 'maria.santos@outlook.com', '+351967654321',
  65.00,
  'a1000003-0000-0000-0000-000000000001',
  '{}'::jsonb,
  NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day'
) ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 4. VISITA — Pedro Álvares, T3 Avenidas Novas, amanhã às 15:00, confirmada
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.visits (
  id, lead_id, listing_id, advertiser_id, buyer_user_id,
  mode, status, starts_at, ends_at,
  buyer_confirmed_at, buyer_confirmation_requested_at,
  created_at, updated_at
) VALUES (
  'f1000001-0000-0000-0000-000000000001',
  'e1000001-0000-0000-0000-000000000001',
  'd1000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'b1000001-0000-0000-0000-000000000001',
  'onsite', 'confirmed',
  (CURRENT_DATE + INTERVAL '1 day' + INTERVAL '15 hours'),
  (CURRENT_DATE + INTERVAL '1 day' + INTERVAL '15 hours 30 minutes'),
  NOW() - INTERVAL '1 hour 40 minutes', NOW() - INTERVAL '1 hour 50 minutes',
  NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour 40 minutes'
) ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- 5. CHAT — conversa Pedro Álvares ↔ Sofia Martins sobre o T3 Avenidas Novas
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.chat_conversations (
  id, advertiser_id, listing_id, lead_id, buyer_user_id,
  status, last_message_at, last_message_preview,
  created_at, updated_at
) VALUES (
  '91000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'd1000001-0000-0000-0000-000000000001',
  'e1000001-0000-0000-0000-000000000001',
  'b1000001-0000-0000-0000-000000000001',
  'active',
  NOW() - INTERVAL '1 hour 40 minutes',
  'Perfeito! Já agendei a visita para amanhã às 15:00 através do site.',
  NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour 40 minutes'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO properia.chat_participants (
  id, conversation_id, advertiser_id, user_id, role, last_read_at, created_at, updated_at
) VALUES
  (
    '92000001-0000-0000-0000-000000000001',
    '91000001-0000-0000-0000-000000000001',
    NULL,
    'b1000001-0000-0000-0000-000000000001',
    'buyer',
    NOW() - INTERVAL '1 hour 40 minutes',
    NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour 40 minutes'
  ),
  (
    '92000002-0000-0000-0000-000000000001',
    '91000001-0000-0000-0000-000000000001',
    'c0000001-0000-0000-0000-000000000001',
    'a1000002-0000-0000-0000-000000000001',
    'advertiser_member',
    NOW() - INTERVAL '1 hour 50 minutes',
    NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour 50 minutes'
  )
ON CONFLICT (id) DO NOTHING;

INSERT INTO properia.chat_messages (
  id, conversation_id, advertiser_id, listing_id, lead_id,
  sender_type, sender_user_id, message_type, body, created_at
) VALUES
  (
    '93000001-0000-0000-0000-000000000001',
    '91000001-0000-0000-0000-000000000001',
    'c0000001-0000-0000-0000-000000000001',
    'd1000001-0000-0000-0000-000000000001',
    'e1000001-0000-0000-0000-000000000001',
    'buyer', 'b1000001-0000-0000-0000-000000000001', 'text',
    'Boa tarde, gostaria de saber se o valor do condomínio está incluído ou qual é a quota mensal?',
    NOW() - INTERVAL '2 hours'
  ),
  (
    '93000002-0000-0000-0000-000000000001',
    '91000001-0000-0000-0000-000000000001',
    'c0000001-0000-0000-0000-000000000001',
    'd1000001-0000-0000-0000-000000000001',
    'e1000001-0000-0000-0000-000000000001',
    'advertiser_member', 'a1000002-0000-0000-0000-000000000001', 'text',
    'Olá Pedro! A quota de condomínio é de 85€/mês e inclui fundo de reserva e manutenção dos elevadores.',
    NOW() - INTERVAL '1 hour 50 minutes'
  ),
  (
    '93000003-0000-0000-0000-000000000001',
    '91000001-0000-0000-0000-000000000001',
    'c0000001-0000-0000-0000-000000000001',
    'd1000001-0000-0000-0000-000000000001',
    'e1000001-0000-0000-0000-000000000001',
    'buyer', 'b1000001-0000-0000-0000-000000000001', 'text',
    'Perfeito! Já agendei a visita para amanhã às 15:00 através do site.',
    NOW() - INTERVAL '1 hour 40 minutes'
  )
ON CONFLICT (id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- Success Log
-- ──────────────────────────────────────────────────────────
-- Migração V72 completada:
-- ✓ Imóvel 4: Apartamento vista para o mar (250.000€, draft) -> Ricardo Oliveira
-- ✓ Lead 1: Pedro Álvares -> T3 Avenidas Novas -> visit_scheduled -> Sofia Martins
-- ✓ Lead 2: Maria Santos  -> Moradia T4 Cascais -> qualified       -> João Silva
-- ✓ Visita confirmada: Pedro Álvares, T3 Avenidas Novas, amanhã 15:00-15:30
-- ✓ Chat: 3 mensagens Pedro <-> Sofia sobre o T3 Avenidas Novas
