-- One review per customer per product (verified-purchase reviews).
CREATE UNIQUE INDEX uq_reviews_product_user ON reviews (product_id, user_id);
