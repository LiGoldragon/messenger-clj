# messenger-clj

`messenger-clj` is the standalone Clojure command line messenger for live Flow
routes in Herdr. It resolves a three-field metaflow name to its registered pane, checks the route
and native harness identity, and submits each durable envelope once.

The public compatibility commands remain available:

```text
hm-send          hm-send-abrupt
hm-list          hm-register
hm-deregister    hm-rebind
hm-move          hm-retire
hm-heartbeat-state
```

They invoke the same `messenger-clj` program and the same typed Datalevin
ledger. There is no Python runtime fallback.

## Message shape

Machine prose is tagged EDN:

```clojure
#msg ["Mind Core Primary" "text"]
```

The vector contains the sender metaflow name and the message text. Time, recipient,
harness kind, route identity, delivery attempts, pending messages, and
retirements stay in the typed ledger.

Living words use a separate variant:

```clojure
#psyche ["Mind Core Primary" "context" "whole verbatim"]
```

Each `#psyche` submission is one envelope containing the sender, context, and
the whole verbatim input. It has no 800-character cap, part-number field,
splitting, overflow file, or pointer. EDN escaping preserves the exact input
bytes represented by the strings, including whitespace, newlines, and Unicode.
A long or multiline envelope sent to Claude may be displayed inside Claude's
`pasted_content` wrapper; that presentation is accepted transport behavior and
does not change the submitted envelope or ledger record.

Several living records can travel in one tagged envelope:

```clojure
#psyches [["Mind Core Primary" "first context" "first whole verbatim"]
          ["Mind Core Primary" "second context" "second whole verbatim"]]
```

The sender name is resolved once from the harness-inherited internal `FLOW_ID`
and its registration, then placed into every record. The
stdin payload is an EDN vector of `[context verbatim]` pairs, so callers cannot
supply a different sender for an entry. Each context and verbatim remains its
own complete string.

`#msg`, `#psyche`, and `#psyches` each carry one whole envelope. Messenger does
not split, truncate, create overflow files, or substitute pointers. The Herdr
0.8 socket protocol limits its complete initial JSON request line to 1,048,576
UTF-8 bytes, excluding the terminating newline. Messenger serializes the exact
`agent.prompt` request first and holds `RelayOverflow` durably before prompting
when that real limit is exceeded. JSON escaping and request metadata count
toward the limit, so there is no smaller fixed message-character cap.

The ledger records the variant, exact input fields, and exact envelope for
every submission attempt. Historical numbered psyche attempts remain readable
in the existing Datalevin state, but new attempts never write part fields.

Pass only the body to `hm-send` or `messenger-clj send`. A field that parses as
one complete `#msg` or `#psyche` form is rejected. Ordinary prose may mention
either tag.

## Use

During development, the Bash launcher runs Babashka against the source tree:

```sh
bin/messenger-clj --help
bin/messenger-clj send "Psyche Core Secondary" 'text'
bin/messenger-clj send "Psyche Core Secondary" --psyche 'why these words matter' 'verbatim words'
printf '%s' 'multiline body' | bin/messenger-clj send "Psyche Core Secondary" --stdin
printf '%s' '[["context" "whole verbatim"]]' | bin/messenger-clj send "Psyche Core Secondary" --psyches --stdin
```

`hm-retire "Aspect Topic Layer"` retires the current registered seat after the
existing exact live/native checks. Its public receipt names the metaflow.
Exact binding, native identity, evidence path and SHA remain in the typed
retirement record. Refusals retain their reason, such as `PaneNotFound` or
`NativeMismatch`, and name the metaflow rather than an internal key.

`--stdin` reads the complete standard input as the machine body or, with
`--psyche CONTEXT`, as that record's verbatim. `--psyches --stdin` reads the
plural EDN payload. This avoids Linux's per-argument size limit for both the
public command and the downstream prompt: Messenger submits the resulting
request through Herdr's Unix socket API rather than placing the envelope in a
Herdr CLI argument.

The `hm-*` scripts are development compatibility launchers. JSON migration is
available only through `messenger-clj import-json`; it is not an operational
authority or fallback.

The Nix package is built with [clj-build](https://github.com/LiGoldragon/clj-build).
`bb.edn` doubles as the `deps.edn` that clj-build resolves once in a
fixed-output derivation; everything after that runs offline. The package holds a
Babashka uberjar built from `src/` in Nix, the pinned Datalevin pod 0.8.25,
`messenger-clj`, and the ten `hm-*` command links. No build product is
committed: the uberjar exists only as the output of the current source. The
build lives in `nix/`; `flake.nix` only indexes it.

```sh
nix build
nix run . -- --help
nix flake check
```

`nix flake check` builds the package, runs it against an empty typed ledger
(`check.nix`), and runs the Clojure test suite below as the `clj-tests` check,
with the pinned pod and a stub `orchestrate`.

Home manages `~/.local/bin/messenger-clj` and the `hm-*` command links from
the immutable package. Its activation admits only exact legacy or known
predecessor command bindings before replacement. The old
`~/.local/libexec/messenger-clj` package pointer is not the command provider.

## State continuity

The final typed state root is `~/.local/state/messenger-clj`. `HM_REGISTRY` may
select another root for tests and isolated operation. The old Python source and
JSON state are retained only as frozen migration inputs.

The installed launcher and this source select the typed state root
`~/.local/state/messenger-clj`. Deployment replaces the package target behind
all ten `hm-*` aliases atomically and does not move or rewrite that database.

Concurrent operations use unique Orchestrate lock names against the same state
path. A competing operation waits for that path lock for a bounded interval;
timeout refuses before a prompt or ledger write.

## Clojure tests

```sh
bb --config bb.edn -e \
  '(require '\''messenger-clj.core-test '\''messenger-clj.public-test '\''messenger-clj.cli-test '\''messenger-clj.typed-store-test '\''messenger-clj.legacy-import-test) (apply clojure.test/run-tests ['\''messenger-clj.core-test '\''messenger-clj.public-test '\''messenger-clj.cli-test '\''messenger-clj.typed-store-test '\''messenger-clj.legacy-import-test])'
```

Set `MESSENGER_CLJ_DATALEVIN_POD` to a Datalevin 0.8.25 `dtlv` to use the
pinned pod instead of fetching it. The suite uses fake Herdr boundaries and
temporary Datalevin stores. It never sends to a live route, but one core test
takes a Lock through the `orchestrate` on `PATH`.


## Public identity contract

Every flow-facing identity argument is one quoted `"Aspect Topic Layer"`.
`hm-send`, `hm-send-abrupt`, `hm-register`, `hm-repair`, `hm-deregister`,
`hm-rebind`, `hm-move`, `hm-retire` and the corresponding `messenger-clj`
operations reject internal IDs and dotted names. `hm-list` displays
`{ Aspect Topic Layer ID }` in its TITLE column, using explicit registration
metadata and the exact six-character flow ID. It never derives a title from
an internal Herdr name or truncates an unsupported ID. Incomplete metadata
or unsupported display IDs refuse without changing registrations.
These display titles are not command targets. `hm-heartbeat-state` and
command receipts retain three-field metaflow names without runtime IDs or
native UUIDs.
The current lookup owner is **messenger-clj's existing typed registry**;
this does not claim that native Flow Nexus already owns the mapping.

The data anatomy is a public `Metaflow` (aspect, topic, layer), an internal
registry key, and an exact native `RouteBinding`. A public name resolves to
one current registered binding under the same reservation as the operation.
Missing or ambiguous targets refuse before delivery. A stale native route
still encounters the existing delivery/repair guards. Historical identity
names survive route removal in `flow/metaflow`; a current binding takes
precedence over history, while ambiguous history is never guessed. Existing
unnamed retired records stay intact and are reported as metadata unavailable.
No title, model, native UUID substring or legacy name supplies metadata.

First registration is `hm-register "Field Topic Quaternary" HERDR_NAME
--session SESSION --native-thread UUID`. The launcher supplies its actual
claimed identity through the runtime-only `HM_REGISTRATION_FLOW_ID`
environment entry; a flow never passes that key as an argument. The normal
`FLOW_ID` inherited from the sender harness remains an internal audit/lock
input. Existing named registrations need no bootstrap key. Optional
`--aspect`, `--topic`, and `--layer` flags must exactly equal the public name.
The same live native route, cross-registration and retirement checks still
run. A different active key for the same name refuses; replacement removes
or retires the old binding normally before registering the new one. A stale
`HM_REGISTRATION_FLOW_ID` does not override a current name.

Exact binding options to repair/deregister/rebind/move remain required;
these are native transport evidence, not alternate public flow identifiers.
Historical retirement import requires an already accounted named registry
identity; it refuses an unidentified old record instead of inventing one.
JSON import keeps internal IDs in its private historical receipt, while its
public summary reports counts. Old ID-bearing envelopes remain readable
history; a pending old-origin envelope cannot be replayed as a new send.

The three live envelope variants use the sender registration's space-separated
name. An unregistered sender or one missing metadata refuses. Message and
psyche payloads pass unchanged; identity conversion never rewrites payloads.
Delivery receipts retain `Transported`, `Presented`, `Held`, and `Uncertain`;
uncertainty still forbids a blind retry. Full runtime causes and internal
attempt/binding records remain separate from public identities.

## Declarative deployment boundary

Field updates the immutable Home package and all default entrypoints together,
with source/JAR parity and typed-store preservation. Launcher-owned bootstrap
calls must change from an ID argument to a public name plus the scoped runtime
registration environment. Sender hooks must use their actual registered
committer name; do not leave ID-based callers on a name-only package. Qualify
all actual sending registrations and the current sole publisher before
activation. Internal historical IDs and old package/store evidence remain.
One registered name-to-name send and the actual received envelope witness
are required after declaration/activation; tests alone do not prove it.

The additive registry name attribute does not rewrite historical attempts,
retirements or route keys. Keep the previous immutable package for supported
backout; it ignores the additional identity attribute. No store deletion,
retired-seat wake, new identity allocation or global enrollment is performed
by this change.

## Transition and held delivery

A transition-marked registration can send its handover. Transition still
holds incoming messages; retirement, missing metadata, NeedsBinding and
route-repair holds remain guards on the sender.

Every new held message retains the exact submitted envelope, including its
original registered sender name, variant, context and verbatim payload.
`hm-repair` can release a held message after the existing exact live-route
checks pass and receiver transition/route holds are cleared. It submits the
saved envelope, never one rebuilt from the releasing seat or current sender
metadata. Retained historical rows without a qualified envelope are refused,
not supplied a guessed sender.

Pending messages are read in persisted enqueue-entity order under the normal
registry reservation, not random UUID or wall-clock order. Releasing any item
except the oldest for its receiver returns `PendingOrder` before route changes
or prompting. Consumption and the pre-prompt attempt remain one transaction;
an uncertain submission is not automatically replayed. This is ordering of
stored enqueue events, not a claim to reconstruct arrival times lost before
historical imports, nor a new automatic queue-draining service.

## Flow-managed address delivery

Public sends first ask the installed Flow CLI `Current` for the exact metaflow
address. Awake, Asleep and Ended addresses use the installed Message CLI, which
identifies the actual subprocess peer through Flow; no sender ID is supplied.
Only an explicit `Current.Unknown` permits the existing legacy registry/pane
route. Missing executables, unavailable sockets, refused or malformed replies
never trigger pane fallback. Home must select matching `flow` and `message`
clients on the messenger process PATH.

An ordinary hm-send body is an Order; psyche records retain their Context and
Verbatim fields in order. Message Delivered, Queued and Woken remain distinct
receipts; Queued does not claim delivery. Pane selection, interrupt and
wait-presented options have no equivalent queue contract and refuse for managed
addresses. Exchange failure after attempting Message is uncertain, never a
reason to resend through a pane. Legacy-only senders require supported Flow
adoption before Message can identify them; this bridge cannot invent bindings.
