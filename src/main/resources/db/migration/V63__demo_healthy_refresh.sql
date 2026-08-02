-- V63 — Demonstração num estado SAUDÁVEL, credível e "vendível".
--
-- Numa demo comercial, a conta de exemplo (agência Prime Living) não pode parecer
-- uma agência a afundar: com datas fixas de seed, TODAS as leads ficavam ">72h fora
-- do prazo" (104 atrasadas, 0 novas, taxa de fecho a bater no fundo). Esta migração:
--   #2  refresca as leads para um pipeline ATIVO e recente (poucas mesmo atrasadas,
--       para o Radar/SLA continuar a fazer sentido);
--   #4  troca as capas com fotos de estilo de vida/pessoas por interiores limpos
--       (reutiliza URLs já presentes na BD, garantidamente renderizáveis);
--   #8  reforça o matching comprador↔imóvel (mais imóveis compatíveis à vista).
--
-- Âmbito: exclusivamente a agência de demonstração (advertiser fixo abaixo).
-- Idempotente e re-executável: pode ser reaplicada para "refrescar" a demo.

DO $$
DECLARE
  demo_adv uuid := 'a219b0d2-8a5a-460e-b0dc-3d79bd4793b4';
BEGIN
  -- SEGURANÇA: só aplica se a agência de demonstração existir NESTA base de dados.
  -- Em produção (sem a demo) esta migração é um NO-OP seguro — sem esta guarda, o
  -- INSERT em buyer_listing_matches com UUIDs fixos violaria a foreign key e faria
  -- o Flyway/arranque da app rebentar (foi o que aconteceu no deploy de 28/07).
  IF NOT EXISTS (SELECT 1 FROM properia.advertisers WHERE id = demo_adv) THEN
    RAISE NOTICE 'V63: agência de demonstração ausente — migração ignorada (no-op).';
    RETURN;
  END IF;

  -- ─────────────────────────────────────────────────────────────────────
  -- #2 · LEADS SAUDÁVEIS
  -- ─────────────────────────────────────────────────────────────────────
  -- Nota: o KPI "fora do prazo" conta leads ABERTAS com created_at > 72h
  -- (ver AdvertiserMetricsService: age = now - created_at, exclui won/lost).
  -- Uma agência saudável não acumula backlog aberto há semanas — as leads
  -- abertas são recentes; as antigas já foram resolvidas.

  -- 1) Fechadas (won/lost): resolvidas ao longo das últimas semanas.
  UPDATE properia.leads
     SET created_at = now() - (interval '5 days' + random() * interval '35 days')
   WHERE advertiser_id = demo_adv AND stage IN ('won', 'lost');
  UPDATE properia.leads
     SET updated_at = created_at + random() * (now() - created_at)
   WHERE advertiser_id = demo_adv AND stage IN ('won', 'lost');

  -- 2) Abertas: entrada recente (<60h) → dentro do prazo (movimento saudável).
  UPDATE properia.leads
     SET created_at = now() - (random() * interval '60 hours')
   WHERE advertiser_id = demo_adv AND stage NOT IN ('won', 'lost');
  UPDATE properia.leads
     SET updated_at = GREATEST(created_at, now() - random() * interval '30 hours')
   WHERE advertiser_id = demo_adv AND stage NOT IN ('won', 'lost');

  -- 3) Mantém ~11 follow-ups genuinamente fora do prazo (4–9 dias) para o
  --    Radar Comercial / SLA continuar a mostrar valor — longe dos 104 iniciais.
  UPDATE properia.leads
     SET created_at = now() - (interval '4 days' + random() * interval '5 days'),
         updated_at = now() - (interval '4 days' + random() * interval '5 days')
   WHERE ctid IN (
     SELECT ctid FROM properia.leads
      WHERE advertiser_id = demo_adv
        AND stage IN ('new', 'contacted', 'qualified')
      ORDER BY random()
      LIMIT 11
   );

  -- ─────────────────────────────────────────────────────────────────────
  -- #4 · CAPAS CREDÍVEIS (interiores limpos, sem lifestyle/pessoas)
  -- ─────────────────────────────────────────────────────────────────────
  UPDATE properia.listing_media m
     SET url = c.cover_url,
         thumbnail_url = c.cover_url
    FROM (
      VALUES
        ('3ac8b041-e7a2-48e8-a69a-77466c04a0c9'::uuid, 'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=1280&q=80'),
        ('125022be-8207-4bb9-a8f4-9970051b99b3'::uuid, 'https://images.unsplash.com/photo-1600566753086-00f18fb6b3ea?auto=format&fit=crop&w=1280&q=80'),
        ('4363fb54-571e-4719-8342-549401837f2b'::uuid, 'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=1280&q=80'),
        ('cebf1531-ebb1-4169-b4aa-6623ce4d1db3'::uuid, 'https://images.unsplash.com/photo-1484154218962-a197022b5858?auto=format&fit=crop&w=1280&q=80'),
        ('b664eef3-03b7-4c0c-8ca3-135beef300e2'::uuid, 'https://images.unsplash.com/photo-1518895949257-7621c3c786d7?auto=format&fit=crop&w=1280&q=80'),
        ('5c37267b-344c-44d2-8f3e-c77c1e16d145'::uuid, 'https://images.unsplash.com/photo-1552321554-5fefe8c9ef14?auto=format&fit=crop&w=1280&q=80'),
        ('df0cf260-3131-4763-a416-d0f9aca07737'::uuid, 'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=1280&q=80'),
        ('02553cc3-744a-492c-9076-029d8d1e650a'::uuid, 'https://images.unsplash.com/photo-1600566753086-00f18fb6b3ea?auto=format&fit=crop&w=1280&q=80'),
        ('284f70e9-c821-404f-b00c-85601f7da223'::uuid, 'https://images.unsplash.com/photo-1484154218962-a197022b5858?auto=format&fit=crop&w=1280&q=80'),
        ('a02f5026-81e1-4663-9d0e-1cd83d613606'::uuid, 'https://images.unsplash.com/photo-1518895949257-7621c3c786d7?auto=format&fit=crop&w=1280&q=80')
    ) AS c(listing_id, cover_url)
   WHERE m.listing_id = c.listing_id
     AND m.is_cover = true;

  -- ─────────────────────────────────────────────────────────────────────
  -- #8 · MATCHING REFORÇADO (compradores ↔ imóveis compatíveis)
  -- Investidor — H. Santos procura apartamento T1–T2 em Lisboa: casamos com os
  -- T2/T1 do centro que ainda não estavam associados. Honesto (tipo+quartos+zona).
  -- ─────────────────────────────────────────────────────────────────────
  INSERT INTO properia.buyer_listing_matches
    (id, buyer_profile_id, listing_id, advertiser_id, match_score, matched_criteria, status, created_at, updated_at)
  SELECT gen_random_uuid(), v.buyer_id, v.listing_id, demo_adv, v.score, v.criteria::jsonb, 'new', now(), now()
  FROM (
    VALUES
      ('9e91a443-a424-4c17-8777-3b37eeca6743'::uuid, '3ac8b041-e7a2-48e8-a69a-77466c04a0c9'::uuid, 74, '["Zona", "Tipo de imóvel", "Quartos"]'),
      ('9e91a443-a424-4c17-8777-3b37eeca6743'::uuid, '5c37267b-344c-44d2-8f3e-c77c1e16d145'::uuid, 71, '["Zona", "Tipo de imóvel", "Quartos"]'),
      ('9e91a443-a424-4c17-8777-3b37eeca6743'::uuid, 'a02f5026-81e1-4663-9d0e-1cd83d613606'::uuid, 66, '["Tipo de imóvel", "Quartos"]')
  ) AS v(buyer_id, listing_id, score, criteria)
  WHERE EXISTS (SELECT 1 FROM properia.buyer_profiles bp WHERE bp.id = v.buyer_id)
    AND EXISTS (SELECT 1 FROM properia.listings l WHERE l.id = v.listing_id)
    AND NOT EXISTS (
      SELECT 1 FROM properia.buyer_listing_matches x
       WHERE x.buyer_profile_id = v.buyer_id AND x.listing_id = v.listing_id
    );

  RAISE NOTICE 'V63 demo refresh aplicado ao advertiser %', demo_adv;
END $$;
