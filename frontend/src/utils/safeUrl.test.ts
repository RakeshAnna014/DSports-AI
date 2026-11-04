import { describe, expect, it } from 'vitest';
import { safeHref } from './safeUrl';

describe('safeHref', () => {
  it('keeps ordinary web addresses', () => {
    expect(safeHref('https://www.victorsport.com/p/1?x=1')).toBe('https://www.victorsport.com/p/1?x=1');
    expect(safeHref('  http://example.com/a ')).toBe('http://example.com/a');
  });

  it.each([
    'javascript:alert(1)', 'JaVaScRiPt:alert(1)', ' javascript:alert(1)', 'data:text/html,<script>alert(1)</script>', 'vbscript:msgbox(1)', 'file:///etc/passwd', 'ftp://example.com/x',
    '//evil.example.net/x', '/relative/path', 'not a url', '', 'https://user:pw@example.com/', 'blob:https://example.com/uuid',
  ])('does not make %j a link', (bad) => {
    expect(safeHref(bad)).toBeUndefined();
  });

  it('handles missing values', () => {
    expect(safeHref(null)).toBeUndefined();
    expect(safeHref(undefined)).toBeUndefined();
  });
});
