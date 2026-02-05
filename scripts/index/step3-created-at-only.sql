-- Step 3: Single index on created_at only
CREATE INDEX idx_product_created_at ON product(created_at DESC);
