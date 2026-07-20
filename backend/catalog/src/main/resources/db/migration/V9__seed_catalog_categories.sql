INSERT INTO categories (id, name, slug, description, status, created_at, updated_at) VALUES
    (gen_random_uuid(), 'Bat', 'bat', 'Cricket bats and paddles', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Ball', 'ball', 'Sport balls for various games', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Shoes', 'shoes', 'Sports footwear and training shoes', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Gloves', 'gloves', 'Protective and performance gloves', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Helmet', 'helmet', 'Protective headgear for sports', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Jersey', 'jersey', 'Team jerseys and sportswear tops', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Accessories', 'accessories', 'Sports accessories and equipment add-ons', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Kit Bag', 'kit-bag', 'Sports bags and equipment carriers', 'ACTIVE', NOW(), NOW());
