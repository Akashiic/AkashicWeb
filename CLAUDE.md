# CLAUDE.md

Entry point for Claude (or any coding assistant) working in this repository.

1. Read [`AGENTS.md`](AGENTS.md): the engineering rules. They apply to you.
2. Read [`docs/PROJECT_STATE.md`](docs/PROJECT_STATE.md), then [`docs/HANDOFF.md`](docs/HANDOFF.md), then [`docs/PLAN.md`](docs/PLAN.md).
3. Check [`docs/DECISIONS.md`](docs/DECISIONS.md) for binding owner decisions and pending questions, and [`docs/ISSUES_REGISTER.md`](docs/ISSUES_REGISTER.md) / GitHub Issues before starting.
4. Look things up in [`docs/research/`](docs/research/) and [`docs/PROBLEM_FIX_MATRIX.md`](docs/PROBLEM_FIX_MATRIX.md) before re-researching. Re-check anything time-sensitive (versions, URLs, hashes).

## Owner and language

The owner is Gabriel (GitHub `Akashiic`, Rede Akashic). Conversation with him is in Brazilian Portuguese; repository documentation, commit messages, issues and code comments are in English. [`docs/PT-BR_RESUMO.md`](docs/PT-BR_RESUMO.md) is the Portuguese summary; keep it in step with `PROJECT_STATE.md`.

## What the owner wants (binding)

- A real, interactive web browser on in-world screens, Web Displays style (D-001, D-002). Not a results board, not a radio, not a command-only kiosk. Alternatives may appear only as internal techniques or clearly labelled safety nets.
- A from-scratch mod with the Web Displays idea, premium GTNH-stack quality (D-003, D-004).
- Sound through AkashicFM's audio technology (D-005); low-delay lives (D-006).

## Hard rules (short form)

- No release tag, production claim or "stable" wording without the evidence a gate requires.
- Label every statement with its evidence grade (AUTHORED / STATIC / BUILD / AUTOMATED / LIVE-OBSERVED / LIVE-APPROVED). A Linux harness result never substitutes for a Windows or in-game observation.
- Never commit secrets, player identifiers, CEF/Chromium binaries, JARs, ZIPs, raw client logs or media captures. Record SHA-256 hashes instead.
- Never `reset`, `clean` or `stash` changes you did not create. Preserve `docs/research/raw/**` as written.
- Mod code stays on Java 8 bytecode (class major 52), including the shipped `org.cef` classes.
- Only `client.web` may touch `org.cef`; the dedicated server must never load natives.
- Never pass `--disable-web-security`. Keep the site policy and internal-address block on both server and client.
- State plainly what you did **not** run.

## When a gate or decision changes

Update `docs/PROJECT_STATE.md`, `docs/PLAN.md` (or `DECISIONS.md`) and `docs/ISSUES_REGISTER.md` in the same commit, citing the evidence. Add an entry to `CHANGELOG.md` under *Unreleased*.
