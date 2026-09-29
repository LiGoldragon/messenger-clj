import contextlib
import hashlib
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import hm


class MessengerTests(unittest.TestCase):
    def setUp(self):
        env = patch.dict('os.environ', {'FLOW_ID': 'sender'})
        env.start()
        self.addCleanup(env.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.m = hm.Messenger(self.temp.name)
        self.agent = dict(session='test', name='receiver', pane_id='w1:p2',
                          terminal_id='original', agent='codex', interactive_ready=True,
                          agent_status='working')
        self.calls = []
        self.lock = patch.object(self.m, 'reservation', lambda flow: contextlib.nullcontext())
        self.lock.start()
        self.addCleanup(self.lock.stop)
        self.api = patch('hm.herdr', self.herdr)
        self.api.start()
        self.addCleanup(self.api.stop)
        self.native_thread = '11111111-2222-3333-4444-555555555555'
        self.m.register('test-flow', 'receiver', 'test', native_thread=self.native_thread)
        self.calls.clear()

    def evidence(self):
        path = Path(self.temp.name) / 'retirement-receipt.md'
        path.write_text('exact retained lifecycle evidence\n')
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def retire(self, flow='test-flow', native_thread=None, allow_absent=False):
        evidence, digest = self.evidence()
        return self.m.retire(flow, 'test', 'w1:p2', 'original', 'receiver', 'codex',
                             native_thread or self.native_thread, evidence, digest, allow_absent)

    def herdr(self, *args):
        self.calls.append(args)
        if 'agent' in args and 'get' in args:
            return {'agent': self.agent}
        if 'process-info' in args:
            return {'process_info': {'foreground_processes': [
                {'pid': 123, 'argv': ['codex', '--thread', self.native_thread]}]}}
        if args[-2:] == ('agent', 'list'):
            return {'agents': [self.agent]}
        return {'type': 'ok'}

    def test_plain_text_is_one_unchanged_argument(self):
        body = 'Notice.{ «hello 世界» }\n$(touch /not-executed)'
        result = self.m.send('test-flow', body)
        self.assertEqual(result, 'Transported.{ test-flow working }')
        prompt = next(call for call in self.calls if 'prompt' in call)
        self.assertEqual(prompt[:5], ('--session', 'test', 'agent', 'prompt', 'w1:p2'))
        self.assertIn(body.replace('»', '\\»'), prompt[-1])
        self.assertTrue(prompt[-1].startswith('Machine.Relay.{ machine sender «'))
        self.assertNotRegex(prompt[-1], r'Machine\.Relay\.\{ e[0-9a-f]{32} ')
        attempt = __import__('json').loads(self.m._attempt_path().read_text().splitlines()[-1])
        self.assertRegex(attempt['id'], r'^[0-9a-f]{32}$')
        self.assertNotIn(attempt['id'], prompt[-1])

    def test_abrupt_orders_escape_before_prompt(self):
        self.m.send('test-flow', 'hello', abrupt=True)
        actions = [call for call in self.calls if 'send-keys' in call or 'prompt' in call]
        self.assertEqual(actions[0], ('--session', 'test', 'agent', 'send-keys', 'w1:p2', 'esc'))
        self.assertEqual(actions[1][:5], ('--session', 'test', 'agent', 'prompt', 'w1:p2'))

    def test_registration_and_delivery_ignore_false_or_missing_readiness(self):
        self.agent['interactive_ready'] = False
        self.m.register('busy-flow', 'receiver', 'test', native_thread=self.native_thread)
        self.assertFalse(any('prompt' in call for call in self.calls))
        self.agent.pop('interactive_ready')
        self.assertEqual(self.m.send('busy-flow', 'deliver once'), 'Transported.{ busy-flow working }')
        prompt = next(call for call in self.calls if 'prompt' in call)
        self.assertEqual(prompt[:5], ('--session', 'test', 'agent', 'prompt', 'w1:p2'))
        self.assertEqual(self.m.read('busy-flow')['native_thread'], self.native_thread)

    def test_replaced_terminal_refuses_send(self):
        self.agent['terminal_id'] = 'replacement'
        with self.assertRaisesRegex(hm.Failure, 'IdentityChanged'):
            self.m.send('test-flow', 'hello')
        self.assertEqual(len(self.calls), 1)

    def test_process_mismatch_holds_without_prompt(self):
        def wrong_process(*args):
            if 'process-info' in args:
                return {'process_info': {'foreground_processes': [
                    {'pid': 123, 'argv': ['codex', '--thread', 'other-thread']} ]}}
            return self.herdr(*args)
        with patch('hm.herdr', wrong_process), self.assertRaisesRegex(hm.Held, 'ProcessMismatch'):
            self.m.send('test-flow', 'stay held')
        self.assertFalse(any('prompt' in call for call in self.calls))

    def test_wait_presented_uses_wait_and_reports_grade(self):
        result = self.m.send('test-flow', 'present this', wait_presented=True)
        self.assertEqual(result, 'Presented.{ test-flow working }')
        prompt = next(call for call in self.calls if 'prompt' in call)
        self.assertEqual(prompt[-2:], ('--timeout', '5000'))
        self.assertIn('--wait', prompt)

    def test_targeted_agent_get_without_session_is_the_exact_selected_session(self):
        """Herdr's real `agent get` payload has no `session` property."""
        actual_shape = dict(self.agent)
        actual_shape.pop('session')
        def get_without_session(*args):
            if 'agent' in args and 'get' in args:
                return {'agent': actual_shape}
            return self.herdr(*args)
        with patch('hm.herdr', get_without_session):
            self.assertEqual(self.m.send('test-flow', 'real Herdr shape'),
                             'Transported.{ test-flow working }')

    def test_missing_or_transition_route_is_pending_hold(self):
        self.m.path('test-flow').unlink()
        with self.assertRaisesRegex(hm.Held, 'NotRegistered'):
            self.m.send('test-flow', 'wait for a binding', hold_seconds=0)
        pending = list((Path(self.temp.name) / 'pending').glob('*.json'))
        self.assertEqual(len(pending), 1)
        self.m.register('test-flow', 'receiver', 'test', native_thread=self.native_thread)
        record = self.m.read('test-flow'); record['transition'] = {'successor_expected': 'test-flow'}
        self.m.path('test-flow').write_text(__import__('json').dumps(record))
        with self.assertRaisesRegex(hm.Held, 'InTransition'):
            self.m.send('test-flow', 'wait for successor', hold_seconds=0)

    def test_conflicting_registration_preserves_original(self):
        self.agent['terminal_id'] = 'replacement'
        with self.assertRaisesRegex(hm.Failure, 'different terminal'):
            self.m.register('test-flow', 'receiver', 'test')
        self.assertEqual(self.m.read('test-flow')['terminal_id'], 'original')

    def test_rebind_renames_the_verified_same_target_and_preserves_native_thread(self):
        self.agent['name'] = 'renamed-receiver'
        result = self.m.rebind('test-flow', 'receiver', 'renamed-receiver', 'test',
                               'w1:p2', 'original', 'codex', self.native_thread)
        self.assertEqual(result, 'Rebound test-flow: receiver -> renamed-receiver (test/w1:p2/original)')
        record = self.m.read('test-flow')
        self.assertEqual(record['name'], 'renamed-receiver')
        self.assertEqual(record['native_thread'], self.native_thread)
        self.assertEqual(record['session'], 'test')
        self.assertEqual(record['pane_id'], 'w1:p2')
        self.assertEqual(record['terminal_id'], 'original')

    def test_rebind_refuses_changed_pane_or_native_thread_without_replacing_record(self):
        self.agent['name'] = 'renamed-receiver'
        self.agent['pane_id'] = 'w1:p2'
        with self.assertRaisesRegex(hm.Failure, 'old binding'):
            self.m.rebind('test-flow', 'receiver', 'renamed-receiver', 'test',
                          'w1:p3', 'original', 'codex', self.native_thread)
        self.assertEqual(self.m.read('test-flow')['name'], 'receiver')

    def test_rebind_refuses_a_name_already_registered_in_its_session(self):
        self.agent['name'] = 'renamed-receiver'
        self.m.register('other-flow', 'renamed-receiver', 'test',
                        native_thread='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
        with self.assertRaisesRegex(hm.Failure, 'already registered'):
            self.m.rebind('test-flow', 'receiver', 'renamed-receiver', 'test',
                          'w1:p2', 'original', 'codex', self.native_thread)
        self.assertEqual(self.m.read('test-flow')['name'], 'receiver')
        with self.assertRaisesRegex(hm.Failure, 'native thread'):
            self.m.rebind('test-flow', 'receiver', 'renamed-receiver', 'test',
                          'w1:p2', 'original', 'codex',
                          'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
        self.assertEqual(self.m.read('test-flow')['name'], 'receiver')

    def move_fixture(self, fail_after_move=False, fail_reverse=False):
        state = {'pane': 'w1:p2', 'workspace': 'w1'}
        def live(*args):
            if args[-2:] == ('agent', 'list'):
                return {'agents': [dict(self.agent, pane_id=state['pane'])]}
            if 'process-info' in args:
                return {'process_info': {'foreground_processes': [{'pid': 123,
                         'argv': ['codex', '--remote']}]}}
            if 'get' in args:
                pane = {'pane_id': state['pane'], 'workspace_id': state['workspace'],
                        'terminal_id': 'original', 'agent': 'codex', 'label': 'receiver'}
                if fail_after_move and state['workspace'] == 'w2':
                    pane['terminal_id'] = 'unexpected'
                return {'pane': pane}
            if 'move' in args:
                if state['workspace'] == 'w2' and fail_reverse:
                    raise hm.Failure('reverse unavailable')
                before = dict(state)
                state['workspace'] = args[args.index('--workspace') + 1]
                state['pane'] = 'w2:p9' if state['workspace'] == 'w2' else 'w1:p8'
                return {'move_result': {'previous_pane_id': before['pane'],
                        'previous_workspace_id': before['workspace'],
                        'pane': {'pane_id': state['pane'],
                                 'workspace_id': state['workspace'],
                                 'terminal_id': 'original'}}}
            raise AssertionError(args)
        return state, live

    def test_move_preserves_native_and_rebinds_exact_new_pane(self):
        state, live = self.move_fixture()
        with patch('hm.herdr', live):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w2')
        self.assertEqual(state['pane'], 'w2:p9')
        self.assertEqual(self.m.read('test-flow')['pane_id'], 'w2:p9')
        self.assertEqual(self.m.read('test-flow')['native_thread'], self.native_thread)

    def test_move_within_workspace_reorders_tab_and_updates_pane_id(self):
        state, live = self.move_fixture()
        with patch('hm.herdr', live):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w1')
        self.assertEqual(state, {'pane': 'w1:p8', 'workspace': 'w1'})
        self.assertEqual(self.m.read('test-flow')['pane_id'], 'w1:p8')

    def test_move_stale_native_or_pid_never_moves(self):
        state, live = self.move_fixture()
        with patch('hm.herdr', live):
            with self.assertRaisesRegex(hm.Failure, 'old route or native'):
                self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                            'codex', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', 123, 'w2')
            with self.assertRaisesRegex(hm.Failure, 'foreground process'):
                self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                            'codex', self.native_thread, 999, 'w2')
        self.assertEqual(state['pane'], 'w1:p2')
        self.assertEqual(self.m.read('test-flow')['pane_id'], 'w1:p2')

    def test_move_postcondition_failure_compensates_and_rebinds_returned_pane(self):
        state, live = self.move_fixture(fail_after_move=True)
        with patch('hm.herdr', live), self.assertRaisesRegex(hm.Failure, 'returned to original workspace'):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w2')
        self.assertEqual(state, {'pane': 'w1:p8', 'workspace': 'w1'})
        self.assertEqual(self.m.read('test-flow')['pane_id'], 'w1:p8')

    def test_move_rejects_duplicate_registered_terminal(self):
        self.m.path('other').write_text(__import__('json').dumps(dict(self.m.read('test-flow'), name='other')))
        state, live = self.move_fixture()
        with patch('hm.herdr', live), self.assertRaisesRegex(hm.Failure, 'another Flow'):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w2')
        self.assertEqual(state['pane'], 'w1:p2')

    def test_move_route_hold_blocks_delivery_after_uncertain_move(self):
        state, live = self.move_fixture()
        def uncertain(*args):
            if 'move' in args:
                raise hm.Failure('uncertain Herdr result')
            return live(*args)
        with patch('hm.herdr', uncertain), self.assertRaisesRegex(hm.Failure, 'uncertain'):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w2')
        self.assertEqual(state['pane'], 'w1:p2')
        self.assertEqual(self.m.read('test-flow')['route_hold'], 'pane_move_in_progress')
        with self.assertRaisesRegex(hm.Failure, 'RouteHold'):
            self.m.send('test-flow', 'do not deliver')
        with self.assertRaisesRegex(hm.Failure, 'held for route repair'):
            self.m.register('test-flow', 'receiver', 'test', native_thread=self.native_thread)
        with self.assertRaisesRegex(hm.Failure, 'held for route repair'):
            self.m.rebind('test-flow', 'receiver', 'renamed-receiver', 'test',
                          'w1:p2', 'original', 'codex', self.native_thread)
        with self.assertRaisesRegex(hm.Failure, 'held for route repair'):
            self.m.move('test-flow', 'test', 'w1:p2', 'original', 'receiver',
                        'codex', self.native_thread, 123, 'w2')

    def test_invalid_body_has_no_side_effect(self):
        for text in ('', '\x1b[1mhi', 'hello\x7f'):
            with self.assertRaises(hm.Failure):
                self.m.send('test-flow', text, abrupt=True)
        self.assertEqual(self.calls, [])

    def test_unknown_and_traversal_refuse(self):
        for flow in ('absent', '../escape'):
            with self.assertRaises(hm.Failure):
                self.m.send(flow, 'hello')
        self.assertEqual(self.calls, [])

    def test_escape_failure_never_prompts(self):
        original = self.herdr
        def fail(*args):
            if 'send-keys' in args:
                raise hm.Failure('Escape unavailable')
            return original(*args)
        with patch('hm.herdr', fail), self.assertRaises(hm.Failure):
            self.m.send('test-flow', 'hello', abrupt=True)
        self.assertFalse(any('prompt' in c for c in self.calls))

    def test_prompt_failure_reports_partial_abrupt(self):
        original = self.herdr
        def fail(*args):
            if 'prompt' in args:
                raise hm.Failure('lost connection')
            return original(*args)
        with patch('hm.herdr', fail), self.assertRaisesRegex(hm.Failure, 'Escape was sent'):
            self.m.send('test-flow', 'hello', abrupt=True)

    def test_blocked_refuses_before_escape(self):
        self.agent['agent_status'] = 'blocked'
        with self.assertRaisesRegex(hm.Failure, 'Blocked'):
            self.m.send('test-flow', 'hello', abrupt=True)
        self.assertEqual(len(self.calls), 1)

    def test_success_exit_with_error_json_is_failure(self):
        with patch('hm.run', return_value='{"error":{"code":"agent_not_found"}}'):
            with self.assertRaises(hm.Failure):
                self.api.stop()
                try:
                    hm.herdr('agent', 'prompt', 'missing', 'hello')
                finally:
                    self.api.start()

    def test_retired_flow_refuses_before_any_herdr_call(self):
        self.retire()
        self.calls.clear()
        with self.assertRaisesRegex(hm.Failure, 'retired'):
            self.m.send('test-flow', 'must not arrive')
        self.assertEqual(self.calls, [])

    def test_malformed_retirement_marker_fails_closed_before_any_herdr_call(self):
        marker = self.m.retired_path('test-flow')
        marker.parent.mkdir()
        marker.write_text('{not json')
        self.calls.clear()
        with self.assertRaisesRegex(hm.Failure, 'unavailable or malformed'):
            self.m.send('test-flow', 'must not arrive')
        self.assertEqual(self.calls, [])

    def test_import_retirement_accepts_absent_route_without_contacting_herdr(self):
        self.m.path('test-flow').unlink()
        self.calls.clear()
        self.retire(allow_absent=True)
        self.assertEqual(self.calls, [])
        self.assertEqual(self.m.retirement('test-flow')['native_thread'], self.native_thread)

    def test_retired_native_thread_cannot_be_registered_as_another_flow(self):
        self.retire()
        self.calls.clear()
        with self.assertRaisesRegex(hm.Failure, 'Native thread'):
            self.m.register('replacement-flow', 'receiver', 'test', native_thread=self.native_thread)
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
