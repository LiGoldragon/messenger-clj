#!/usr/bin/env python3
"""Small, session-aware messaging through Herdr's existing CLI."""
import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


# The compensation skill that documents these commands.  Every --help names it
# so that whoever changes this tool updates the skill in the same landing.
SKILL_NOTE = ('Documented by the compensation-hacky-messenger skill '
              '(Curriculum skills/compensation-hacky-messenger.md). '
              'Update that skill with any change to this tool.')


class Failure(Exception):
    pass


class Held(Failure):
    """A message was retained without issuing a prompt."""
    pass


def run(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise Failure(f'{argv[0]} failed or timed out; submission may be uncertain: {error}') from error
    if result.returncode:
        raise Failure((result.stderr or result.stdout).strip() or f'{argv[0]} failed')
    return result.stdout


def herdr(*args):
    try:
        reply = json.loads(run(['herdr', *args]))
    except ValueError as error:
        raise Failure('Herdr returned invalid JSON; do not blindly retry a send') from error
    if 'error' in reply:
        raise Failure(f"Herdr: {reply['error']}")
    return reply.get('result', reply)


def quote(value):
    return '«' + str(value).replace('\\', '\\\\').replace('»', '\\»') + '»'


# How each harness is hard-abrupted, witnessed live by flow 1ac573 on 2026-09-17
# against a Claude probe pane (interrupt confirmed by the harness's own
# "Interrupted" marker, with a 6000-item task stopping at 4393).
#
# codex:  one Escape interrupts, and `herdr agent prompt` submits by itself.
# claude: the FIRST Escape is eaten by the input editor when Claude's
#         editorMode is vim (it leaves INSERT for NORMAL); only the SECOND
#         reaches the harness as an interrupt. A redundant Escape on an
#         already-idle Claude is harmless. After an interrupt Claude leaves
#         prompted text sitting unsubmitted in the input box, so Enter is what
#         actually delivers it.
# KNOWN LIMITATION, observed in the same live run: the escapes interrupt the
# turn but do NOT clear whatever text is already sitting in the input box, so a
# residual unsubmitted line and the new message are concatenated on one line.
# The recipient still received and answered the message, but the delivered text
# was not clean. Clearing the input before prompting is the next fix here.
ABRUPT_KEYS = {
    'codex': {'interrupt': ('esc',), 'submit': ()},
    'claude': {'interrupt': ('esc', 'esc'), 'submit': ('enter',)},
}


class Messenger:
    def __init__(self, root=None):
        self.root = Path(root or os.environ.get('HM_REGISTRY',
            str(Path.home() / '.local/state/hacky-messenger'))).absolute()

    def path(self, flow):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}', flow):
            raise Failure('Flow ID must contain only letters, digits, underscores, or hyphens')
        return self.root / (flow + '.json')

    def _attempt_path(self):
        return self.root / 'attempts.jsonl'

    def _record_attempt(self, flow, reason, message, record=None, grade=None):
        """Append one fsynced send decision.  This never controls delivery."""
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        attempt = {
            'id': uuid.uuid4().hex,
            'at': dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
            'flow': flow, 'reason': reason, 'grade': grade,
            'binding': ({key: record.get(key) for key in ('pane_id', 'terminal_id', 'native_thread')}
                        if record else None),
        }
        path = self._attempt_path()
        with path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(attempt, sort_keys=True, separators=(',', ':')) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        if reason in {'NotRegistered', 'InTransition'}:
            pending = self.root / 'pending'
            pending.mkdir(parents=True, exist_ok=True, mode=0o700)
            destination = pending / (attempt['id'] + '.json')
            temporary = destination.with_suffix('.tmp')
            payload = dict(attempt, message=message, state='held')
            with temporary.open('w', encoding='utf-8') as stream:
                json.dump(payload, stream, sort_keys=True)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(destination)
        return attempt

    def _held(self, flow, reason, message, record=None):
        attempt = self._record_attempt(flow, reason, message, record)
        raise Held(f'Held.{{ {flow} {reason} attempt-{attempt["id"][:12]} }}')

    @staticmethod
    def _in_transition(record):
        return bool(record and (record.get('transition') or record.get('state') == 'transition'))

    def _wait_for_route(self, flow, message, hold_seconds):
        """Wait outside the registry reservation so a registrar can publish."""
        deadline = time.monotonic() + hold_seconds
        last_reason = 'NotRegistered'
        while True:
            try:
                record = self.read(flow)
                if not self._in_transition(record):
                    return record
                last_reason = 'InTransition'
            except Failure:
                last_reason = 'NotRegistered'
            if time.monotonic() >= deadline:
                self._held(flow, last_reason, message)
            time.sleep(min(0.2, max(0.0, deadline - time.monotonic())))

    def retired_path(self, flow):
        # Retirement is deliberately separate from ordinary route repair.  A
        # missing registration is not evidence that its Flow has ended.
        self.path(flow)
        return self.root / 'retired' / (flow + '.json')

    @staticmethod
    def route_fields(record):
        return {key: record[key] for key in ('session', 'name', 'pane_id', 'terminal_id', 'agent')}

    @staticmethod
    def native_thread(value):
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9-]{16,96}', value):
            raise Failure('An exact native thread ID is required')
        return value

    def retirement(self, flow):
        path = self.retired_path(flow)
        if not path.exists():
            return None
        try:
            marker = json.loads(path.read_text())
            if (marker.get('version') != 1 or marker.get('state') != 'retired'
                    or marker.get('flow') != flow):
                raise ValueError('header')
            record = marker['record']
            self.route_fields(record)
            self.native_thread(marker['native_thread'])
            evidence = marker['evidence']
            if (not isinstance(evidence['path'], str) or not evidence['path'].startswith('/')
                    or not re.fullmatch(r'[0-9a-f]{64}', evidence['sha256'])):
                raise ValueError('evidence')
            evidence_path = Path(evidence['path'])
            if (not evidence_path.is_file()
                    or hashlib.sha256(evidence_path.read_bytes()).hexdigest() != evidence['sha256']):
                raise ValueError('evidence bytes')
        except (OSError, ValueError, KeyError, TypeError) as error:
            # An unreadable lifecycle marker must never be treated as no marker.
            raise Failure(f'Retirement marker for {flow} is unavailable or malformed; nothing sent') from error
        return marker

    def assert_not_retired(self, flow):
        marker = self.retirement(flow)
        if marker:
            raise Failure(f'Flow {flow} is retired by {marker["evidence"]["path"]}; nothing sent')

    def assert_native_not_retired(self, native_thread, flow):
        retired = self.root / 'retired'
        if not retired.exists():
            return
        for path in retired.glob('*.json'):
            other = self.retirement(path.stem)
            if other and other['native_thread'] == native_thread and path.stem != flow:
                raise Failure(f'Native thread {native_thread} is retired as Flow {path.stem}; use a fresh native session')

    @staticmethod
    def evidence(path, expected_sha256):
        evidence_path = Path(path)
        if not evidence_path.is_absolute() or not evidence_path.is_file():
            raise Failure('Retirement evidence must be an existing absolute file')
        if not re.fullmatch(r'[0-9a-f]{64}', expected_sha256):
            raise Failure('Retirement evidence requires a SHA-256 digest')
        actual = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
        if actual != expected_sha256:
            raise Failure('Retirement evidence SHA-256 does not match; nothing changed')
        return {'path': str(evidence_path.resolve()), 'sha256': actual}

    @contextmanager
    def reservation(self, flow):
        # One short reservation serializes sends and registration in this registry.
        owner = os.environ.get('FLOW_ID', flow)
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}', owner):
            raise Failure('Invalid FLOW_ID')
        reply = run(['orchestrate', f'Lock.{{ HackyMessengerDelivery {owner} [ {quote(self.root)} ] «Register or submit through Herdr» }}'])
        match = re.match(r'Locked\.\{\s+(\d+)\b', reply)
        if not match:
            raise Failure(f'Reservation refused: {reply.strip()}')
        try:
            yield
        finally:
            released = run(['orchestrate', f'Release.{match[1]}'])
            if not released.startswith('Released.'):
                raise Failure(f'Release failed: {released.strip()}')

    def agents(self, session=None):
        sessions = ([{'name': session, 'running': True}] if session else
                    herdr('session', 'list', '--json')['sessions'])
        found = []
        for item in sessions:
            if item['running']:
                for agent in herdr('--session', item['name'], 'agent', 'list')['agents']:
                    found.append(dict(agent, session=item['name']))
        return found

    def register(self, flow, name, session=None, native_thread=None):
        path = self.path(flow)
        with self.reservation(flow):
            self.assert_not_retired(flow)
            prior = self.read(flow) if path.exists() else None
            if prior and prior.get('route_hold'):
                raise Failure('Registration is held for route repair; inspect exact terminal before registration')
            native_thread = (native_thread or (prior or {}).get('native_thread')
)
            # The native identity is the anti-alias binding: a display name or
            # pane can be recycled after reaping, but a retired native session
            # cannot silently become a new Flow registration.
            native_thread = self.native_thread(native_thread)
            self.assert_native_not_retired(native_thread, flow)
            matches = [a for a in self.agents(session) if a.get('name') == name]
            if len(matches) != 1:
                raise Failure(f'Expected one live agent named {name}; found {len(matches)}. Use --session.')
            agent = matches[0]
            if not agent.get('agent'):
                raise Failure('Agent kind is unavailable')
            record = {k: agent[k] for k in ('session', 'name', 'pane_id', 'terminal_id', 'agent')}
            if native_thread:
                record['native_thread'] = native_thread
            if prior and self.route_fields(prior) != self.route_fields(record):
                raise Failure('Flow already registered to a different terminal; retire its registry file explicitly')
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = path.with_suffix('.tmp')
            with temporary.open('w') as stream:
                json.dump(record, stream)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(path)
        return f'Registered {flow}: {name} ({record["session"]})'

    def read(self, flow):
        try:
            record = json.loads(self.path(flow).read_text())
            for key in ('session', 'name', 'pane_id', 'terminal_id', 'agent'):
                if not isinstance(record[key], str) or not record[key]:
                    raise ValueError(key)
            return record
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise Failure(f'No valid registration for {flow}: {error}') from error

    def deregister(self, flow, session, pane_id, terminal_id, name):
        path = self.path(flow)
        expected = {'session': session, 'pane_id': pane_id, 'terminal_id': terminal_id, 'name': name}
        with self.reservation(flow):
            actual = self.read(flow)
            if any(actual[key] != value for key, value in expected.items()):
                raise Failure('Registration differs from the explicitly revalidated stale route')
            path.unlink()
        return f'Deregistered stale {flow}: {name} ({session}/{pane_id}/{terminal_id})'

    def rebind(self, flow, old_name, new_name, session, pane_id, terminal_id, agent,
               native_thread):
        """Atomically replace only a Flow's verified display-name binding.

        The caller supplies the complete old route and native identity.  The
        stored registration must match it exactly, while Herdr must expose
        exactly one live agent with the requested new name at that same route.
        This keeps a name alignment from becoming a terminal reassignment.
        """
        path = self.path(flow)
        expected = {'session': session, 'name': old_name, 'pane_id': pane_id,
                    'terminal_id': terminal_id, 'agent': agent}
        if not all(isinstance(value, str) and value for value in expected.values()):
            raise Failure('Rebind requires every exact old route identity field')
        if not isinstance(new_name, str) or not new_name or new_name == old_name:
            raise Failure('Rebind requires a distinct nonempty new agent name')
        native_thread = self.native_thread(native_thread)
        temporary = path.with_suffix('.tmp')
        with self.reservation(flow):
            self.assert_not_retired(flow)
            actual = self.read(flow)
            if actual.get('route_hold'):
                raise Failure('Registration is held for route repair; rebind refused')
            if self.route_fields(actual) != expected:
                raise Failure('Registration differs from the explicitly revalidated old binding')
            stored_native_thread = self.native_thread(actual.get('native_thread'))
            if stored_native_thread != native_thread:
                raise Failure('Registration differs from the explicitly revalidated native thread')
            collisions = [other_flow for other_flow in self.root.glob('*.json')
                          if other_flow != path
                          and self.read(other_flow.stem).get('session') == session
                          and self.read(other_flow.stem).get('name') == new_name]
            if collisions:
                raise Failure(f'New agent name {new_name} is already registered in {session}')
            matches = [item for item in self.agents(session)
                       if all(item.get(key) == value for key, value in {
                           'session': session, 'name': new_name, 'pane_id': pane_id,
                           'terminal_id': terminal_id, 'agent': agent}.items())]
            if len(matches) != 1:
                raise Failure(f'Expected one live agent at the exact rebind target; found {len(matches)}')
            replacement = dict(actual)
            replacement['name'] = new_name
            try:
                with temporary.open('w') as stream:
                    json.dump(replacement, stream)
                    stream.write('\n')
                    stream.flush()
                    os.fsync(stream.fileno())
                temporary.replace(path)
            finally:
                if temporary.exists():
                    temporary.unlink()
        return f'Rebound {flow}: {old_name} -> {new_name} ({session}/{pane_id}/{terminal_id})'

    def move(self, flow, session, pane_id, terminal_id, name, agent, native_thread,
             process_pid, workspace):
        """Move one exact live pane while delivery holds the registry reservation.

        Herdr gives a cross-workspace move a new pane ID, even on reversal.
        The registry therefore follows the verified terminal, never an assumed
        old pane ID. A post-move failure attempts a compensating move and binds
        the final verified location before releasing the reservation.
        """
        expected = {'session': session, 'name': name, 'pane_id': pane_id,
                    'terminal_id': terminal_id, 'agent': agent}
        if not all(isinstance(value, str) and value for value in expected.values()):
            raise Failure('Move requires the complete old route')
        native_thread = self.native_thread(native_thread)
        if not isinstance(process_pid, int) or process_pid <= 0:
            raise Failure('Move requires a witnessed positive foreground process PID')
        if not re.fullmatch(r'w[A-Za-z0-9]+', workspace):
            raise Failure('Move requires an exact Herdr workspace ID')
        path = self.path(flow)
        with self.reservation(flow):
            self.assert_not_retired(flow)
            record = self.read(flow)
            if record.get('route_hold'):
                raise Failure('Registration is held for route repair; move refused')
            if self.route_fields(record) != expected or record.get('native_thread') != native_thread:
                raise Failure('Move old route or native thread differs from registration')
            source = herdr('--session', session, 'pane', 'get', pane_id)['pane']
            old_workspace = source['workspace_id']
            self._verify_move_target(expected, source, process_pid, native_thread)
            # A duplicate route would make any later send ambiguous.
            for other_path in self.root.glob('*.json'):
                if other_path != path and self.read(other_path.stem).get('terminal_id') == terminal_id:
                    raise Failure('Terminal is registered to another Flow')
            # A process or host failure between Herdr's move and the registry
            # replacement must fail closed rather than expose the old pane.
            self._write_move_route(path, record, pane_id, hold=True)
            moved = None
            try:
                result = herdr('--session', session, 'pane', 'move', pane_id, '--new-tab',
                               '--workspace', workspace, '--label', source.get('label', name),
                               '--no-focus')['move_result']
                moved = result['pane']
                if (result.get('previous_pane_id') != pane_id
                        or result.get('previous_workspace_id') != old_workspace
                        or moved.get('workspace_id') != workspace):
                    raise Failure('Herdr move result differs from requested route')
                self._verify_move_target(expected, moved, process_pid, native_thread)
                self._write_move_route(path, record, moved['pane_id'])
            except Exception as error:
                # A failed Herdr call can be uncertain. Do not guess a pane ID.
                if moved is None:
                    raise Failure(f'Move failed or is uncertain; inspect exact terminal before routing: {error}') from error
                try:
                    reverse = herdr('--session', session, 'pane', 'move', moved['pane_id'],
                                    '--new-tab', '--workspace', old_workspace, '--label',
                                    source.get('label', name), '--no-focus')['move_result']['pane']
                    self._verify_move_target(expected, reverse, process_pid, native_thread)
                    self._write_move_route(path, record, reverse['pane_id'])
                except Exception as rollback_error:
                    # If reversal fails, retain a usable route only if the moved
                    # terminal is still independently verified at its new pane.
                    try:
                        self._verify_move_target(expected, moved, process_pid, native_thread)
                        self._write_move_route(path, record, moved['pane_id'])
                    except Exception as route_error:
                        raise Failure(f'Move and compensation failed; delivery held for manual route repair: {route_error}') from rollback_error
                    raise Failure(f'Move validation failed; terminal remains at verified destination: {error}') from error
                raise Failure(f'Move failed; terminal was returned to original workspace with new pane ID: {error}') from error
        return f'Moved {flow}: {old_workspace}/{pane_id} -> {workspace}/{moved["pane_id"]} ({terminal_id})'

    def _verify_move_target(self, expected, pane, process_pid, native_thread):
        session = expected['session']
        pane_id = pane['pane_id']
        live_pane = herdr('--session', session, 'pane', 'get', pane_id)['pane']
        if (pane.get('terminal_id') != expected['terminal_id']
                or live_pane.get('terminal_id') != expected['terminal_id']
                or live_pane.get('agent') != expected['agent']):
            raise Failure('Moved pane terminal or harness identity changed')
        info = herdr('--session', session, 'pane', 'process-info', '--pane', pane_id)['process_info']
        processes = info.get('foreground_processes', [])
        if not any(item.get('pid') == process_pid for item in processes):
            raise Failure('Moved pane foreground process identity changed')
        # Claude exposes its UUID in argv. Codex remote CLI does not; the
        # unchanged PID, terminal, and stored native binding supply that check.
        if expected['agent'] == 'claude' and not any(
                native_thread in item.get('argv', []) for item in processes):
            raise Failure('Moved Claude pane native session identity changed')
        matches = [item for item in self.agents(session)
                   if all(item.get(key) == value for key, value in {
                       'session': session, 'name': expected['name'], 'pane_id': pane_id,
                       'terminal_id': expected['terminal_id'], 'agent': expected['agent']}.items())]
        if len(matches) != 1:
            raise Failure('Moved pane has no unique matching Herdr agent')

    @staticmethod
    def _write_move_route(path, old_record, pane_id, hold=False):
        replacement = dict(old_record)
        replacement['pane_id'] = pane_id
        if hold:
            replacement['route_hold'] = 'pane_move_in_progress'
        else:
            replacement.pop('route_hold', None)
        temporary = path.with_suffix('.tmp')
        try:
            with temporary.open('w') as stream:
                json.dump(replacement, stream)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)

    def retire(self, flow, session, pane_id, terminal_id, name, agent, native_thread,
               evidence_path, evidence_sha256, allow_absent=False):
        """Persist an evidence-bound retirement before any route is removed.

        `allow_absent` is only for importing a retirement after a separately
        witnessed deregistration. It never creates or contacts a Herdr target.
        """
        path = self.path(flow)
        expected = {'session': session, 'pane_id': pane_id, 'terminal_id': terminal_id,
                    'name': name, 'agent': agent}
        if not all(isinstance(value, str) and value for value in expected.values()):
            raise Failure('Retirement requires every exact route identity field')
        native_thread = self.native_thread(native_thread)
        evidence = self.evidence(evidence_path, evidence_sha256)
        marker_path = self.retired_path(flow)
        with self.reservation(flow):
            existing_marker = self.retirement(flow)
            if existing_marker:
                if existing_marker['record'] == expected and existing_marker['native_thread'] == native_thread:
                    return f'Already retired {flow}: marker retained'
                raise Failure(f'Flow {flow} already has a different retirement marker')
            if path.exists():
                actual = self.route_fields(self.read(flow))
                if actual != expected:
                    raise Failure('Registration differs from the explicitly revalidated retirement route')
            elif not allow_absent:
                raise Failure('No current registration; use import-retirement only with retained exact evidence')
            marker = {
                'version': 1,
                'state': 'retired',
                'flow': flow,
                'record': expected,
                'native_thread': native_thread,
                'evidence': evidence,
                'retired_by': os.environ.get('FLOW_ID', ''),
                'retired_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
            }
            marker_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = marker_path.with_suffix('.tmp')
            with temporary.open('w') as stream:
                json.dump(marker, stream, sort_keys=True)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(marker_path)
        return f'Retired {flow}: delivery is blocked before Herdr routing'

    @staticmethod
    def matches(record, agent):
        # `agent list` is enriched with its session by `agents()`, but the
        # targeted `herdr --session S agent get PANE` response intentionally
        # returns only the agent object.  The command line already binds that
        # response to S, so require an embedded session only when present.
        fields = ('name', 'pane_id', 'terminal_id', 'agent')
        return (all(record[k] == agent.get(k) for k in fields)
                and (agent.get('session') is None or record['session'] == agent['session']))

    def _target_agent(self, record):
        value = herdr('--session', record['session'], 'agent', 'get', record['pane_id'])
        agent = value.get('agent', value)
        if not isinstance(agent, dict) or not self.matches(record, agent):
            raise Failure('IdentityChanged')
        return agent

    def _process_matches(self, record):
        info = herdr('--session', record['session'], 'pane', 'process-info', '--pane', record['pane_id'])
        processes = info.get('process_info', info).get('foreground_processes', [])
        native_thread = self.native_thread(record.get('native_thread'))
        for process in processes:
            argv = process.get('argv', [])
            argv_text = ' '.join(str(value) for value in argv) if isinstance(argv, list) else str(argv)
            if native_thread in argv_text:
                return
            if record['agent'] == 'claude' and isinstance(process.get('pid'), int):
                session_file = Path.home() / '.claude' / 'sessions' / f"{process['pid']}.json"
                try:
                    if json.loads(session_file.read_text()).get('sessionId') == native_thread:
                        return
                except (OSError, ValueError, TypeError):
                    pass
        raise Failure('ProcessMismatch')

    @staticmethod
    def _relay(flow, recipient, message):
        heard = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
        seat = os.environ.get('MESSAGING_SEAT', 'unknown')
        def quoted(value): return '«' + value.replace('\\', '\\\\').replace('»', '\\»') + '»'
        return (f'Machine.Relay.{{ machine {flow} {quoted(heard)} {seat} '
                f'[ {recipient} ] {quoted(message)} {quoted("")} }}')

    def send(self, flow, message, abrupt=False, wait_presented=False, hold_seconds=10):
        self.path(flow)
        if not message.strip() or any((ord(c) < 32 and c not in '\n\t') or 127 <= ord(c) <= 159 for c in message):
            raise Failure('Message must be nonempty and contain no terminal control characters')
        if len(message.encode()) > 65536:
            raise Failure('Message exceeds 64 KiB')
        if not os.environ.get('FLOW_ID'):
            raise Failure('Set FLOW_ID to your own flow ID before sending')
        # A missing or explicitly transitional binding is held briefly.  The
        # re-resolution always names this exact Flow; it never selects a peer
        # or falls back to another transport.
        self._wait_for_route(flow, message, hold_seconds)
        with self.reservation(flow):
            # Check under the same reservation as retirement.  A marker written
            # after this point cannot retract an already-issued prompt, but it
            # blocks every later send before any Herdr call.
            self.assert_not_retired(flow)
            try:
                record = self.read(flow)
            except Failure:
                self._held(flow, 'NotRegistered', message)
            if self._in_transition(record):
                self._held(flow, 'InTransition', message, record)
            if record.get('route_hold'):
                self._held(flow, 'RouteHold', message, record)
            try:
                live = self._target_agent(record)
            except Failure as error:
                reason = str(error) if str(error) in {'IdentityChanged', 'ProcessMismatch'} else 'PaneMissing'
                self._held(flow, reason, message, record)
            if live.get('agent_status') == 'blocked':
                self._held(flow, 'Blocked', message, record)
            if live.get('agent_status') not in {'idle', 'working', 'done'}:
                self._held(flow, 'Uncertain', message, record)
            try:
                self._process_matches(record)
            except Failure as error:
                reason = 'ProcessMismatch' if str(error) == 'ProcessMismatch' else 'PaneMissing'
                self._held(flow, reason, message, record)
            if abrupt and record['agent'] not in ABRUPT_KEYS:
                raise Failure(f'Hard-abrupt is not supported for {record["agent"]}; nothing sent')
            args = ['--session', record['session'], 'agent']
            # Herdr accepts pane targets; terminal identity was checked above.
            target = record['pane_id']
            if abrupt:
                for key in ABRUPT_KEYS[record['agent']]['interrupt']:
                    herdr(*args, 'send-keys', target, key)
            try:
                framed = self._relay(os.environ['FLOW_ID'], flow, message)
                prompt_args = ('prompt', target, framed)
                if wait_presented:
                    prompt_args += ('--wait', '--timeout', '5000')
                herdr(*args, *prompt_args)
                if abrupt:
                    for key in ABRUPT_KEYS[record['agent']]['submit']:
                        herdr(*args, 'send-keys', target, key)
            except (Failure, ValueError) as error:
                if wait_presented and 'agent_prompt_stalled' in str(error):
                    self._held(flow, 'Stalled', message, record)
                prefix = 'Escape was sent; prompt failed or is uncertain' if abrupt else 'Prompt failed or is uncertain'
                attempt = self._record_attempt(flow, 'Uncertain', message, record)
                raise Failure(f'Uncertain.{{ {flow} attempt-{attempt["id"][:12]} }} {prefix}: {error}') from error
            try:
                post = self._target_agent(record)
                if post.get('terminal_id') != record['terminal_id']:
                    raise Failure('IdentityChanged')
            except Failure:
                attempt = self._record_attempt(flow, 'Uncertain', message, record)
                raise Failure(f'Uncertain.{{ {flow} attempt-{attempt["id"][:12]} }} post-check changed')
            grade = 'Presented' if wait_presented else 'Transported'
            self._record_attempt(flow, 'sent', message, record, grade)
        return f'{grade}.{{ {flow} {live.get("agent_status", "unknown")} }}'

    def listing(self):
        records = {}
        for path in sorted(self.root.glob('*.json')):
            records[path.stem] = self.read(path.stem)
        rows = ['FLOW\tAGENT\tSESSION\tSTATE']
        seen = set()
        for agent in self.agents():
            flows = [f for f, r in records.items() if self.matches(r, agent)]
            seen.update(flows)
            rows.append('\t'.join([','.join(flows) or '-', agent.get('name') or '-',
                                   agent['session'], agent['agent_status']]))
        for flow in records.keys() - seen:
            record = records[flow]
            rows.append(f'{flow}\t{record["name"]}\t{record["session"]}\tSTALE')
        return '\n'.join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    register = sub.add_parser('register')
    register.add_argument('flow')
    register.add_argument('name')
    register.add_argument('--session')
    register.add_argument('--native-thread', help='exact native thread identity')
    deregister = sub.add_parser('deregister')
    deregister.add_argument('flow')
    deregister.add_argument('--session', required=True)
    deregister.add_argument('--pane-id', required=True)
    deregister.add_argument('--terminal-id', required=True)
    deregister.add_argument('--name', required=True)
    rebind = sub.add_parser('rebind', help='atomically align a Flow registration with a renamed live agent')
    rebind.add_argument('flow')
    rebind.add_argument('new_name')
    rebind.add_argument('--old-name', required=True)
    rebind.add_argument('--session', required=True)
    rebind.add_argument('--pane-id', required=True)
    rebind.add_argument('--terminal-id', required=True)
    rebind.add_argument('--agent', required=True)
    rebind.add_argument('--native-thread', required=True)
    move = sub.add_parser('move', help='guarded cross-workspace transfer of one exact live Flow pane')
    move.add_argument('flow')
    move.add_argument('workspace')
    move.add_argument('--session', required=True)
    move.add_argument('--pane-id', required=True)
    move.add_argument('--terminal-id', required=True)
    move.add_argument('--name', required=True)
    move.add_argument('--agent', required=True)
    move.add_argument('--native-thread', required=True)
    move.add_argument('--process-pid', required=True, type=int)
    def retirement_arguments(command):
        command.add_argument('flow')
        command.add_argument('--session', required=True)
        command.add_argument('--pane-id', required=True)
        command.add_argument('--terminal-id', required=True)
        command.add_argument('--name', required=True)
        command.add_argument('--agent', required=True)
        command.add_argument('--native-thread', required=True)
        command.add_argument('--evidence', required=True)
        command.add_argument('--evidence-sha256', required=True)
    retire = sub.add_parser('retire', help='persist a retirement marker before route removal')
    retirement_arguments(retire)
    imported_retirement = sub.add_parser('import-retirement', help='import a post-deregistration retirement from retained exact evidence')
    retirement_arguments(imported_retirement)
    sub.add_parser('list')
    for name in ('send', 'send-abrupt'):
        send = sub.add_parser(name)
        send.add_argument('flow')
        send.add_argument('message')
        send.add_argument('--wait-presented', action='store_true',
                          help='wait at most five seconds for Herdr lifecycle activity')
        send.add_argument('--hold-seconds', type=float, default=10,
                          help='bounded wait for a missing or transitional binding (default: 10)')
    for command in (parser, *sub.choices.values()):
        command.epilog = SKILL_NOTE
    args = parser.parse_args()
    messenger = Messenger()
    try:
        if args.operation == 'register':
            result = messenger.register(args.flow, args.name, args.session, args.native_thread)
        elif args.operation == 'deregister':
            result = messenger.deregister(args.flow, args.session, args.pane_id, args.terminal_id, args.name)
        elif args.operation == 'rebind':
            result = messenger.rebind(args.flow, args.old_name, args.new_name, args.session,
                                      args.pane_id, args.terminal_id, args.agent,
                                      args.native_thread)
        elif args.operation == 'move':
            result = messenger.move(args.flow, args.session, args.pane_id, args.terminal_id,
                                    args.name, args.agent, args.native_thread,
                                    args.process_pid, args.workspace)
        elif args.operation in ('retire', 'import-retirement'):
            result = messenger.retire(args.flow, args.session, args.pane_id, args.terminal_id,
                                      args.name, args.agent, args.native_thread, args.evidence,
                                      args.evidence_sha256,
                                      allow_absent=args.operation == 'import-retirement')
        elif args.operation == 'list':
            result = messenger.listing()
        else:
            if args.hold_seconds < 0 or args.hold_seconds > 60:
                raise Failure('--hold-seconds must be between 0 and 60')
            result = messenger.send(args.flow, args.message, args.operation == 'send-abrupt',
                                    args.wait_presented, args.hold_seconds)
        print(result)
    except (Failure, OSError) as error:
        print(str(error) if isinstance(error, Held) else f'hm: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
