-- Step 5: Reverse composite (created_at DESC, category) — experimental
-- Demonstrates why column order matters in composite indexes
CREATE INDEX idx_product_created_category ON product(created_at DESC, category);
