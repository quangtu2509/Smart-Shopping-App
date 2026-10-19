const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const collection = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../postman/auth00.postman_collection.json'), 'utf8'));
const skipped = Symbol('Postman skipped request');
const item = name => {
    const value = collection.item.find(it => it.name === name);
    assert.ok(value, 'Expected request exists');
    return value;
};
function variables(initial = {}) {
    const values = new Map(Object.entries(initial));
    return { get: key => values.get(key), set: (key, value) => values.set(key, value), unset: key => values.delete(key) };
}
function fixture({ automatic = false, status = 429, body = {}, resetPassword } = {}) {
    const local = variables({ otp_code: 'obsolete-code' });
    const environment = variables({ ...(automatic ? { fixture_url: 'http://127.0.0.1/fixture/code' } : {}),
        ...(resetPassword ? { reset_password: resetPassword } : {}), otp_code: 'obsolete-code' });
    const collectionVariables = variables({ run_email: 'fixture@gmail.com', run_password: 'ExistingFixtureAb!', challenge_id: 'original', otp_code: 'obsolete-code' });
    const failures = [];
    const pm = {
        environment, collectionVariables, failures,
        skipped: false,
        execution: { skipRequest: () => { pm.skipped = true; throw skipped; } },
        variables: { get: key => local.get(key) ?? environment.get(key) ?? collectionVariables.get(key),
            set: local.set, unset: local.unset, replaceIn: value => value.replace(/\{\{\$guid\}\}/g, randomUUID()) },
        response: { code: status, json: () => body, to: { have: { status: expected => assert.equal(status, expected) } } },
        test: (name, block) => { try { block(); } catch { failures.push(name); } },
        expect: value => ({ to: { eql: expected => assert.deepEqual(value, expected), be: { oneOf: choices => assert.ok(choices.includes(value)) } } }),
    };
    return pm;
}
function execute(request, phase, pm) {
    for (const event of request.event ?? []) {
        if (event.listen === phase) {
            try { vm.runInNewContext(event.script.exec.join('\n'), { pm, console: { warn: () => {} } }, { timeout: 1000 }); }
            catch (error) { if (error === skipped) return; throw error; }
        }
    }
}
function resolvedBody(request, pm) {
    return JSON.parse(request.request.body.raw.replace(/\{\{([^}]+)\}\}/g, (_, key) => {
        const value = pm.variables.get(key);
        assert.ok(value, 'Request placeholder must resolve to a local value');
        return value;
    }));
}
const reset = item('API-AUTH-16 Reset password');
const replay = item('Reset grant replay rejected');
for (const request of [reset, replay]) {
    test(`${request.name}: manual reset requires an explicit password`, () => {
        const pm = fixture();
        execute(request, 'prerequest', pm);
        assert.equal(pm.skipped, true);
        assert.equal(pm.variables.get('reset_password'), undefined);
    });
    test(`${request.name}: uses the caller's private password in both fields`, () => {
        const pm = fixture({ resetPassword: 'PrivateFixtureAb!' });
        pm.collectionVariables.set('reset_token', 'fixture-grant');
        execute(request, 'prerequest', pm);
        assert.equal(pm.skipped, false);
        const body = resolvedBody(request, pm);
        assert.equal(body.password, 'PrivateFixtureAb!');
        assert.equal(body.confirm_password, body.password);
    });
}
test('fixture generates a fresh reset password per run and reuses it for replay', () => {
    const first = fixture({ automatic: true });
    const second = fixture({ automatic: true });
    for (const pm of [first, second]) execute(reset, 'prerequest', pm);
    const password = first.variables.get('reset_password');
    assert.ok(typeof password === 'string' && password.length >= 10);
    assert.match(password, /[A-Z]/); assert.match(password, /[a-z]/); assert.match(password, /[^a-zA-Z0-9\s]/);
    assert.notEqual(password, second.variables.get('reset_password'));
    execute(replay, 'prerequest', first);
    assert.equal(password, first.variables.get('reset_password'));
});
const resend = item('API-AUTH-10 Resend cooldown');
test('automatic resend requires 429 and rejects 202 or server failures', () => {
    for (const status of [429, 202, 500]) {
        const pm = fixture({ automatic: true, status });
        execute(resend, 'test', pm);
        assert.equal(pm.failures.length === 0, status === 429);
    }
});
test('manual resend during cooldown keeps the current code and challenge', () => {
    const pm = fixture({ status: 429 });
    execute(resend, 'test', pm);
    assert.equal(pm.failures.length, 0);
    assert.equal(pm.variables.get('otp_code'), 'obsolete-code');
    assert.equal(pm.collectionVariables.get('challenge_id'), 'original');
});
test('manual resend after cooldown accepts 202, updates challenge and clears stale OTP in all scopes', () => {
    const pm = fixture({ status: 202, body: { data: { challenge_id: 'replacement' } } });
    execute(resend, 'test', pm);
    assert.equal(pm.failures.length, 0);
    assert.equal(pm.collectionVariables.get('challenge_id'), 'replacement');
    assert.equal(pm.variables.get('otp_code'), undefined);
    assert.equal(pm.environment.get('otp_code'), undefined);
    assert.equal(pm.collectionVariables.get('otp_code'), undefined);
});
test('manual resend does not accept server failures', () => {
    const pm = fixture({ status: 500 });
    execute(resend, 'test', pm);
    assert.ok(pm.failures.length > 0);
});
test('manual resend rejects an incomplete 202 response without discarding the current challenge', () => {
    const pm = fixture({ status: 202 });
    execute(resend, 'test', pm);
    assert.ok(pm.failures.length > 0);
    assert.equal(pm.collectionVariables.get('challenge_id'), 'original');
    assert.equal(pm.variables.get('otp_code'), 'obsolete-code');
});
