-- Drop all custom indexes (ignore errors if not exist)
-- MySQL does not support DROP INDEX IF EXISTS, so use separate statements
-- The run-explain.sh script handles errors with || true
DROP INDEX idx_product_category ON product;
DROP INDEX idx_product_created_at ON product;
DROP INDEX idx_product_category_created ON product;
DROP INDEX idx_product_created_category ON product;
