# Research index

Done 2026-10-05..10 in the research chat: five parallel research agents, one planning agent and two adversarial reviewers, plus probes run in a Linux cloud container. Each Markdown file is rendered from the agent's raw JSON in [`raw/`](raw/), with errata added where a later stream or review corrected it.

| File | Topic |
|---|---|
| [01-webdisplays-code.md](01-webdisplays-code.md) | Web Displays 1.12.2 code map, the modern fork and its branches, 1.7.10 history, port effort, licences |
| [02-issue-catalog.md](02-issue-catalog.md) | 590 issue titles across WD/MCEF trackers and the 1.7.10-era forum, by root cause, with fixes |
| [03-cef-engine.md](03-cef-engine.md) | java-cef, jcefmaven, CinemaMod MCEF, CCBlueX; OSR, audio handler, cost (has errata on codecs) |
| [04-gtnh-integration.md](04-gtnh-integration.md) | GTNH Java/LWJGL variants, Angelica, lwjgl3ify, threading, input, packaging, AkashicFM audio bridge |
| [05-codecs-live-relay-rights.md](05-codecs-live-relay-rights.md) | YouTube live formats, OBS codec CEF build, latency, rights (its relay part is abandoned, D-008) |
| [06-plan-reviews.md](06-plan-reviews.md) | Feasibility and technical reviews of the first (kiosk-scope) plan |
| [07-forbidden-pack-inventory.md](07-forbidden-pack-inventory.md) | The real Forbidden Series client pack (TEOF-3.6) and server composition: libraries, Angelica settings, Crucible, plugins |
| [`raw/`](raw/) | Raw JSON of every agent, including the superseded kiosk-scope plan (`06-plan-kiosk-scope.json`) |

Grades: **VERIFIED** = seen in code, docs, a page or a command output (with a source); **INFERRED** = reasoning or estimate. Mapping to the repository grades is in [`../PROJECT_STATE.md`](../PROJECT_STATE.md).

Order of precedence when files disagree: owner decisions ([`../DECISIONS.md`](../DECISIONS.md)) > [`../PLAN.md`](../PLAN.md) > stream 05 > the reviews > streams 01-04. Known corrections:

- CinemaMod CEF 116 natives **do** decode H.264/AAC (stream 05 corrects stream 03).
- Angelica's render-bounds registry exists only in Angelica 2.x (review corrects stream 04).
- `DirectByteBuffer(long,int)` does not exist on Java 21 (review corrects stream 04).
- The first plan's "kiosk" scope and "relay as primary" were replaced by the interactive scope (D-002) and the codec build. TSE data and the relay were abandoned (D-008); the raw JSON still mentions them as history.

Container paths inside the raw JSON (`/home/user/...`, `scratchpad/...`) do not exist here. Probe sources kept: [`../../spikes`](../../spikes).
