// node --test lib/localTestGuard.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { assertLocalTestDatabase } from './localTestGuard.mjs';

const LOCAL = 'http://127.0.0.1:8080';
/** A fake database: answers the identity query with `row` and the "is my match here" query with `count`; records every query it was asked. */
const fake = (row, count = '1') => { const asked = []; const sql = (q) => { asked.push(q); return q.includes('count(*)') ? count : row; }; sql.asked = asked; return sql; };

test('the local test database passes, before and after a match exists', () => {
  assertLocalTestDatabase(LOCAL, fake('dsports|socket'), null);
  assertLocalTestDatabase('http://localhost:8080', fake('dsports|socket'), 'abc');
  assertLocalTestDatabase('http://[::1]:8080', fake('dsports|socket'), null);
});

test('a database with another name is refused (production-looking names included)', () => {
  for (const name of ['dsports_prod', 'postgres', 'dsports2', 'DSPORTS', '']) {
    assert.throws(() => assertLocalTestDatabase(LOCAL, fake(`${name}|socket`), null), /the database is ".*", not "dsports"/, name);
  }
});

test('a database reached over the network is refused even if it is called dsports', () => {
  assert.throws(() => assertLocalTestDatabase(LOCAL, fake('dsports|10.0.0.5'), null), /over the network/);
});

test('a remote API address is refused before the database is even asked', () => {
  for (const url of ['http://10.3.34.177:8080', 'https://api.example.com', 'http://127.0.0.1.evil.test:8080', 'http://localhost.evil.test']) {
    const sql = fake('dsports|socket');
    assert.throws(() => assertLocalTestDatabase(url, sql, null), /not this machine/, url);
    assert.equal(sql.asked.length, 0, `${url}: no query was sent`);
  }
});

test('a clean-up is refused when the match this run made is not in this database', () => {
  assert.throws(() => assertLocalTestDatabase(LOCAL, fake('dsports|socket', '0'), 'abc'), /not in this database/);
});
