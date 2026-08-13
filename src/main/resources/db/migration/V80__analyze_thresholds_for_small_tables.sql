-- ─────────────────────────────────────────────────────────────────────────────
-- Estatísticas do planeador para as tabelas da pesquisa.
--
-- O PROBLEMA, medido em produção: a página /imoveis demorava 12 segundos, com
-- apenas 2 imóveis publicados. A consulta de detalhe da pesquisa (8 LEFT JOIN +
-- 3 LATERAL) executava em 0,5 ms quando corrida à mão, mas 13,8 s no caminho
-- normal. O EXPLAIN explicou porquê:
--
--     Sort (cost=1729703868 rows=512539060) (actual time=11708 rows=2)
--     Planning Time: 3 ms
--     Execution Time: 12113 ms
--
-- O planeador estimava 512 MILHÕES de linhas para tabelas com 6. Com estimativas
-- assim, o executor reserva estruturas de hash e sort gigantescas antes de
-- produzir a primeira linha — daí os 12 segundos gastos sem ler mais do que uma
-- página de disco.
--
-- A causa: `last_analyze` e `last_autoanalyze` estavam a NULL em TODAS estas
-- tabelas. O autoanalyze só dispara ao fim de
-- `autovacuum_analyze_threshold` (50) + `scale_factor` (0.1) × n_live_tup
-- alterações. Uma base nova, ou recém-limpa, nunca chega às 50 — portanto nunca
-- recolhe estatísticas, e o planeador fica cego indefinidamente. É um problema
-- que se agrava justamente quando há POUCOS dados, que é o estado de qualquer
-- instalação nova.
--
-- A correção: baixar o limiar nestas tabelas para que o autoanalyze corra desde
-- as primeiras linhas. E um ANALYZE imediato, para que o efeito não dependa de
-- esperar pela próxima passagem do autovacuum.
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    t text;
    tabelas text[] := ARRAY[
        'listings',
        'listing_pricing',
        'listing_location',
        'listing_features',
        'listing_commercial',
        'listing_zone_scores',
        'listing_media',
        'listing_detail_views',
        'listing_price_history',
        'listing_ai_vision',
        'listing_room_details',
        'listing_commercial_details',
        'listing_zone_snapshots',
        'advertisers',
        'leads'
    ];
BEGIN
    FOREACH t IN ARRAY tabelas LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'properia' AND table_name = t
        ) THEN
            EXECUTE format(
                'ALTER TABLE properia.%I SET ('
                || 'autovacuum_analyze_threshold = 5, '
                || 'autovacuum_analyze_scale_factor = 0.05, '
                || 'autovacuum_vacuum_threshold = 25)', t);
            EXECUTE format('ANALYZE properia.%I', t);
        END IF;
    END LOOP;
END $$;
