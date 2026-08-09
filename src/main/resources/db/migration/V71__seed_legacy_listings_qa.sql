-- ============================================================
-- PROPERIA — V71: Seed QA Listings — CENTURY 21 LEGACY TEAM
-- Popula 3 imóveis atribuídos aos 3 utilizadores criados na V68/V70,
-- para testar o fluxo de atribuição/reatribuição por perfil (RBAC).
-- ============================================================

-- Imóvel 1: T3 Avenidas Novas, Lisboa — Sofia Martins (Admin)
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
  'd1000001-0000-0000-0000-000000000001',
  'qa-t3-avenidas-novas-lisboa',
  'c0000001-0000-0000-0000-000000000001',
  'a1000002-0000-0000-0000-000000000001',
  'manual', 'published', 'sale', 'apartment',
  'T3 Avenidas Novas, Lisboa',
  'T3 AVENIDAS NOVAS LISBOA',
  'Apartamento T3 remodelado nas Avenidas Novas, próximo de transportes e comércio.',
  'apartamento t3 remodelado nas avenidas novas proximo de transportes e comercio',
  520000.00, 'EUR',
  3, 2.0,
  'Lisboa', 'Lisboa', 'Avenidas Novas', 'PT',
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

-- Imóvel 2: Moradia T4 em Cascais — João Silva (Sales)
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
  'd1000002-0000-0000-0000-000000000001',
  'qa-moradia-t4-cascais',
  'c0000001-0000-0000-0000-000000000001',
  'a1000003-0000-0000-0000-000000000001',
  'manual', 'published', 'sale', 'villa',
  'Moradia T4 em Cascais',
  'MORADIA T4 EM CASCAIS',
  'Moradia T4 com jardim e piscina, a 5 minutos da praia de Cascais.',
  'moradia t4 com jardim e piscina a 5 minutos da praia de cascais',
  980000.00, 'EUR',
  4, 3.0,
  'Cascais', 'Lisboa', 'Cascais e Estoril', 'PT',
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

-- Imóvel 3: T2 Renovado no Chiado — Ricardo Oliveira (Owner)
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
  'd1000003-0000-0000-0000-000000000001',
  'qa-t2-renovado-chiado',
  'c0000001-0000-0000-0000-000000000001',
  'a1000001-0000-0000-0000-000000000001',
  'manual', 'published', 'sale', 'apartment',
  'T2 Renovado no Chiado',
  'T2 RENOVADO NO CHIADO',
  'Apartamento T2 totalmente renovado no coração do Chiado, com traços originais preservados.',
  'apartamento t2 totalmente renovado no coracao do chiado com tracos originais preservados',
  430000.00, 'EUR',
  2, 1.0,
  'Lisboa', 'Lisboa', 'Santa Maria Maior', 'PT',
  NOW(), NOW(), NOW()
) ON CONFLICT (public_id) DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- Success Log
-- ──────────────────────────────────────────────────────────
-- Migração V71 completada:
-- ✓ T3 Avenidas Novas, Lisboa (520.000€)  -> Sofia Martins (Admin)
-- ✓ Moradia T4 em Cascais (980.000€)       -> João Silva (Sales)
-- ✓ T2 Renovado no Chiado (430.000€)       -> Ricardo Oliveira (Owner)
-- Prontos para a matriz de testes RBAC de atribuição/reatribuição.
