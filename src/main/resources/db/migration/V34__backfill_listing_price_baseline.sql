-- Backfill: regista o preço atual como baseline do histórico para anúncios
-- publicados que ainda não têm qualquer registo de preço. Assim o gráfico de
-- evolução passa a aparecer logo na próxima alteração de preço.
-- Seguro: só insere quando NÃO existe histórico (não altera anúncios já alterados).
INSERT INTO properia.listing_price_history (listing_id, price_amount, price_currency, recorded_at)
SELECT l.id, l.price_amount, COALESCE(l.price_currency, 'EUR'), COALESCE(l.published_at, l.created_at)
FROM properia.listings l
WHERE l.price_amount IS NOT NULL
  AND l.status = 'published'
  AND NOT EXISTS (
    SELECT 1 FROM properia.listing_price_history h WHERE h.listing_id = l.id
  );
