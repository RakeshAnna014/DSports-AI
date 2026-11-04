-- Separate database for the AI service (embeddings, conversations, audit, usage).
-- Keeps derived/AI data out of the commerce database.
SELECT 'CREATE DATABASE dsports_ai OWNER dsports'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'dsports_ai')\gexec
