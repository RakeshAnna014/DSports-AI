/** Links to pages on other sites come from stored data (a buyer entered them, or a research source supplied them). Only plain http(s) addresses become a clickable link; anything else
 *  (javascript:, data:, vbscript:, file:, a relative path, a malformed string) is not rendered as a link at all. The server validates the same rule on input; this is the second layer. */
export const safeHref = (value: string | null | undefined): string | undefined => {
  if (!value) return undefined;
  try {
    const url = new URL(value.trim());
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password ? url.toString() : undefined;
  } catch {
    return undefined;
  }
};
