# messenger-clj architecture

`messenger-clj` has no daemon or socket. Each invocation reads the typed
Datalevin store, checks Herdr, and performs at most one prompt submission.

## Runtime

`messenger-clj.main` owns command parsing. `messenger-clj.core` owns route
validation, Herdr interaction, delivery decisions, and Orchestrate
reservations. `messenger-clj.typed-store` owns the Datalevin schema and typed
queries. `messenger-clj.legacy-import` is a one way, explicit JSON migration
boundary.

Development uses the Bash launcher in `bin/messenger-clj`. Deployment uses the
Nix package's compiled Babashka uberscript and pinned Datalevin pod. Both enter
the same namespace and command parser. The `hm-*` names are links to the
deployed `messenger-clj` executable; the executable maps each link name to its
subcommand.

## Store

Routes, attempts, pending messages, and retirements share one Datalevin
database at `~/.local/state/messenger-clj`. The first managed deployment from
the transitional installation must move the old typed root while both paths
are locked and replace all launchers in the same activation. No operation
reads the frozen Python JSON registry as a fallback.

Every registry write and send takes an Orchestrate reservation over the state
root. Each operation uses a unique reservation name and waits for path
contention for a bounded interval. A send persists its attempt before
prompting. Once prompt submission may have occurred, uncertainty is recorded
and the messenger never retries.

## Delivery

A send validates the body and sender, resolves the stored route against live
Herdr state, checks terminal and harness identity, and persists a submitting
attempt. It then submits one `#msg` EDN envelope. Presentation is reported only
when the selected Herdr operation supplies that observation.

Registration, rebind, move, and deregistration require exact route identity.
Retirement takes only the Flow ID: it reads the registered route, requires one
exact live Herdr agent at that session, pane, and terminal with a matching
harness and native thread, and writes its own evidence file under the state
root before recording the marker and removing the route. Its refusals are typed
`RetireRefused.{ FLOW Reason }` and change nothing. Retirement blocks later
registration or delivery to that native thread. `import-retirement` keeps the
explicit identity and evidence form for retained historical evidence.

## Limits

Identity checking and prompting are separate Herdr calls, so a terminal can be
replaced between them. A transport or presentation grade is not a read receipt.
