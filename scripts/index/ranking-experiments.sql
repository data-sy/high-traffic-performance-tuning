-- Phase 3-2: Ranking Query Index Experiments
-- Target query:
--   SELECT oi.product_id, SUM(oi.quantity) as sales_count
--   FROM order_item oi
--   GROUP BY oi.product_id
--   ORDER BY sales_count DESC
--   LIMIT 100

-- ============================================
-- Experiment 1: No index (baseline)
-- ============================================
-- No index to create. Run EXPLAIN on baseline state.

EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_item oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- ============================================
-- Experiment 2: Single index on product_id
-- ============================================
CREATE INDEX idx_orderitem_product_id ON order_item(product_id);

EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_item oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- ============================================
-- Experiment 3: Covering index (product_id, quantity)
-- ============================================
CREATE INDEX idx_orderitem_product_quantity ON order_item(product_id, quantity);

EXPLAIN SELECT oi.product_id, SUM(oi.quantity) as sales_count
FROM order_item oi
GROUP BY oi.product_id
ORDER BY sales_count DESC
LIMIT 100;

-- ============================================
-- Cleanup (run after experiments)
-- ============================================
-- DROP INDEX idx_orderitem_product_id ON order_item;
-- DROP INDEX idx_orderitem_product_quantity ON order_item;
