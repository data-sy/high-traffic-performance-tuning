-- 쿠폰 발급 측정 리셋: 각 버전 측정 시작 전 호출 (coupon id=1, total_qty=100, issued=0)
DELETE FROM coupon_issue WHERE coupon_id = 1;
DELETE FROM coupon WHERE id = 1;
INSERT INTO coupon (id, name, total_qty, issued, created_at) VALUES (1, '한정 쿠폰', 100, 0, NOW());
