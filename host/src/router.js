'use strict';
// Dispatch of decoded game -> host messages. Pure: the screen manager and the host callbacks are
// injected, so the routing rules are unit-tested without Electron.
//
// Rules: nothing but WELCOME, PING and QUIT is accepted before WELCOME; screen messages for an unknown
// screen are dropped; unknown types are ignored (forward compatibility within a major); a malformed
// message is logged and dropped (only broken framing closes the link, see link.js).
// Two exceptions fail closed instead of being dropped, because both carry the site policy:
//  - a malformed WELCOME is a failed handshake (onBadWelcome; the host exits with EXIT.HANDSHAKE);
//  - a malformed POLICY switches the host to deny-all (onBadPolicy), it never keeps an older policy.

const P = require('./protocol');
const M = require('./messages');

const LOG_EVERY = 100; // after the first few, log one in N rejections so a flood cannot fill the log

class Router {
  /**
   * deps: screens {create(id, spec), get(id), destroy(id)}, onWelcome(welcome), onBadWelcome(error),
   * onPolicy(policy), onBadPolicy(error), onPing(screenId, nonce), onQuit(), log
   */
  constructor(deps) {
    this.d = deps;
    this.welcomed = false;
    this.rejected = 0;
  }

  route(msg) {
    try {
      this._route(msg);
    } catch (err) {
      this._reject(msg, err.code || err.message || String(err));
    }
  }

  _route(msg) {
    const T = P.T;
    switch (msg.type) {
      case T.WELCOME: {
        if (this.welcomed) return this._reject(msg, 'duplicate-welcome');
        let welcome;
        try {
          welcome = M.parseWelcome(P.decodeJson(msg.payload));
        } catch (err) {
          this._reject(msg, err.code || err.message || String(err));
          this.d.onBadWelcome(err);
          return undefined;
        }
        this.welcomed = true;
        this.d.onWelcome(welcome);
        return undefined;
      }
      case T.PING:
        this.d.onPing(msg.screenId, M.parsePing(P.decodeJson(msg.payload)).nonce);
        return undefined;
      case T.QUIT:
        this.d.onQuit();
        return undefined;
      default:
        break;
    }
    if (!this.welcomed) return this._reject(msg, 'before-welcome');
    switch (msg.type) {
      case T.POLICY: {
        let policy;
        try {
          policy = M.parsePolicy(P.decodeJson(msg.payload));
        } catch (err) {
          this._reject(msg, err.code || err.message || String(err));
          this.d.onBadPolicy(err);
          return undefined;
        }
        this.d.onPolicy(policy);
        return undefined;
      }
      case T.CREATE:
        if (msg.screenId === 0) return this._reject(msg, 'screen-0');
        this.d.screens.create(msg.screenId, M.parseCreate(P.decodeJson(msg.payload)));
        return undefined;
      case T.DESTROY:
        this.d.screens.destroy(msg.screenId);
        return undefined;
      default:
        break;
    }
    const screen = this.d.screens.get(msg.screenId);
    if (!screen) {
      if (isScreenMessage(msg.type)) this._reject(msg, 'unknown-screen');
      return undefined;
    }
    switch (msg.type) {
      case T.NAVIGATE: return screen.navigate(M.parseNavigate(P.decodeJson(msg.payload)));
      case T.INPUT: return screen.input(P.decodeJson(msg.payload));
      case T.AUDIO: return screen.setAudio(M.parseAudio(P.decodeJson(msg.payload)));
      case T.RENDER: return screen.render(M.parseRender(P.decodeJson(msg.payload)));
      case T.RESIZE: return screen.resize(M.parseResize(P.decodeJson(msg.payload)));
      case T.SYNC: {
        const done = screen.sync(M.parseSync(P.decodeJson(msg.payload)));
        if (done && typeof done.catch === 'function') done.catch(err => this._reject(msg, String(err && err.message)));
        return undefined;
      }
      default:
        return undefined; // unknown type: ignored on purpose
    }
  }

  _reject(msg, why) {
    this.rejected++;
    if (this.rejected <= 20 || this.rejected % LOG_EVERY === 0) {
      this.d.log.warn('dropped ' + P.typeName(msg.type) + ' screen=' + msg.screenId + ': ' + why + ' (total ' + this.rejected + ')');
    }
    return undefined;
  }
}

function isScreenMessage(type) {
  const T = P.T;
  return [T.NAVIGATE, T.INPUT, T.AUDIO, T.RENDER, T.RESIZE, T.SYNC].includes(type);
}

module.exports = { Router };
