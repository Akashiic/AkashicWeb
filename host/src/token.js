'use strict';
// Session token delivery (ADR-003 amendment): 32 hex chars on stdin followed by "\n", so the token
// never appears in the process list. Fallback: AKW_TOKEN, set by Java only in the child environment.

const TOKEN_RE = /^[0-9a-fA-F]{32}$/;
const MAX_STDIN_BYTES = 256;

function isValidToken(t) {
  return typeof t === 'string' && TOKEN_RE.test(t);
}

/**
 * Reads the token from `stream` (process.stdin in production) up to the first newline, end of
 * stream or `timeoutMs`; falls back to env.AKW_TOKEN. Resolves {token, source} or null.
 * Never rejects: a GUI-subsystem exe on Windows may have no usable stdin at all, which is exactly
 * the case the environment fallback exists for.
 */
function readToken(stream, env, timeoutMs = 3000) {
  const fromEnv = () => (env && isValidToken(env.AKW_TOKEN) ? { token: env.AKW_TOKEN.toLowerCase(), source: 'env' } : null);
  if (!stream || typeof stream.on !== 'function') return Promise.resolve(fromEnv());
  return new Promise(resolve => {
    let text = '';
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      stream.removeListener('data', onData);
      stream.removeListener('end', onEnd);
      stream.removeListener('error', onEnd);
      try { stream.pause(); } catch (e) { /* stream already gone: nothing to release */ }
      const line = text.split('\n')[0].trim();
      resolve(isValidToken(line) ? { token: line.toLowerCase(), source: 'stdin' } : fromEnv());
    };
    const onData = chunk => {
      text += Buffer.isBuffer(chunk) ? chunk.toString('latin1') : String(chunk);
      if (text.includes('\n') || text.length >= MAX_STDIN_BYTES) finish();
    };
    const onEnd = () => finish();
    const timer = setTimeout(finish, timeoutMs);
    stream.on('data', onData);
    stream.on('end', onEnd);
    stream.on('error', onEnd);
    try { if (typeof stream.resume === 'function') stream.resume(); } catch (e) { finish(); }
  });
}

module.exports = { TOKEN_RE, isValidToken, readToken };
