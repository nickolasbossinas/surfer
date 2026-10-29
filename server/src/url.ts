// Turns what the user typed in the URL bar into a URL to load.

const ALLOWED_SCHEMES = new Set(['http:', 'https:', 'about:', 'chrome:']);

const IPV4 = /^(\d{1,3})(\.\d{1,3}){3}(:\d+)?(\/.*)?$/;
const HOST_PORT = /^[a-z0-9.-]+:\d+(\/.*)?$/i;
// A dotted host name ending in an alphabetic TLD, optionally followed by a path.
const DOMAIN = /^([a-z0-9-]+\.)+[a-z][a-z0-9-]{1,62}\.?(:\d+)?([/?#].*)?$/i;
const LOCALHOST = /^localhost(:\d+)?([/?#].*)?$/i;

/**
 * Returns the URL to navigate to, or null if the input is empty or uses a
 * scheme the browser must not open (file:, javascript:, ...).
 */
export function resolveInput(input: string, searchTemplate: string): string | null {
  const text = input.trim();
  if (text === '') return null;

  if (!/\s/.test(text)) {
    // Bare hosts first: "localhost:8080" also looks like a "localhost:" scheme.
    if (IPV4.test(text) || LOCALHOST.test(text) || HOST_PORT.test(text)) return `http://${text}`;
    if (/^[a-z][a-z0-9+.-]*:/i.test(text)) {
      let url: URL;
      try {
        url = new URL(text);
      } catch {
        return search(text, searchTemplate);
      }
      return ALLOWED_SCHEMES.has(url.protocol) ? url.href : null;
    }
    if (DOMAIN.test(text)) return `https://${text}`;
  }
  return search(text, searchTemplate);
}

function search(text: string, template: string): string {
  return template.replace('%s', encodeURIComponent(text));
}
