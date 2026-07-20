INSERT INTO sports (id, name, slug, description, status, created_at, updated_at) VALUES
    (gen_random_uuid(), 'Cricket', 'cricket', 'Bat-and-ball team sport originating in England', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Football', 'football', 'Team sport played with a spherical ball between two teams of 11 players', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Badminton', 'badminton', 'Racket sport played with shuttlecock across a net', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Basketball', 'basketball', 'Team sport where two teams score points by shooting through a hoop', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Tennis', 'tennis', 'Racket sport played individually or in doubles across a net', 'ACTIVE', NOW(), NOW()),
    (gen_random_uuid(), 'Volleyball', 'volleyball', 'Team sport where two teams hit a ball over a net', 'ACTIVE', NOW(), NOW());
