// The safety check for e2e scripts that bypass the database's guards (session_replication_role = replica). Pure: the caller passes in how to ask the database,
// so the refusals can be tested without one. Throws unless everything says "this is the local test database".
export const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);
export const TEST_CONTAINER = 'dsports-postgres';
export const TEST_DATABASE = 'dsports';

/**
 * @param {string} apiUrl the backend the script talks to (must be loopback)
 * @param {(query: string) => string} sql runs one query in the test container's database and returns the trimmed text
 * @param {string|null} matchId when given (before a clean-up), the match this run made must be found in this very database
 */
export function assertLocalTestDatabase(apiUrl, sql, matchId) {
  const apiHost = new URL(apiUrl).hostname;
  if (!LOCAL_HOSTS.has(apiHost)) throw new Error(`refusing to run: the API host is "${apiHost}", not this machine (loopback only)`);
  const row = sql(`SELECT current_database() || '|' || coalesce(inet_server_addr()::text, 'socket')`);
  const [database, address] = row.split('|');
  if (database !== TEST_DATABASE) throw new Error(`refusing to run: the database is "${database}", not "${TEST_DATABASE}"`);
  if (address !== 'socket') throw new Error(`refusing to run: the database was reached over the network (${address}), not the container's own socket`);
  if (matchId && sql(`SELECT count(*) FROM matches WHERE id = '${matchId}'`) !== '1') {
    throw new Error('refusing to clean up: the match this run made is not in this database, so it is not the one the API wrote to');
  }
}
