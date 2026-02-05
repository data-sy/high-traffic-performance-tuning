-- Step 4: Composite index (category, created_at DESC) — optimal
CREATE INDEX idx_product_category_created ON product(category, created_at DESC);
