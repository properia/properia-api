-- [PERF] Pesquisa pública combina sempre status='published' com business_type (=),
-- property_type (IN) e um intervalo de preço. Índices de coluna única não se combinam
-- bem para este padrão; um índice PARCIAL COMPOSTO sobre imóveis publicados serve
-- diretamente a query dominante ("apartamentos à venda até X€") e mantém-se pequeno
-- (só linhas publicadas), com baixo custo de escrita.
--
-- Ordem das colunas: igualdades primeiro (business_type, property_type), intervalo por
-- último (price_amount) — a regra para índices B-tree com predicados de range.
--
-- NOTA: validar com EXPLAIN ANALYZE em dados de produção e afinar a ordem/colunas
-- conforme o mix real de filtros (ex.: adicionar district se a maioria filtra por zona).
-- Em tabela já muito grande, preferir CREATE INDEX CONCURRENTLY fora de transação.

CREATE INDEX IF NOT EXISTS "idx_listings_search_published"
    ON "properia"."listings" ("business_type", "property_type", "price_amount")
    WHERE "status" = 'published';
