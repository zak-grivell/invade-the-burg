const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync('src/main/resources/browser/input-visibility.js', 'utf8');
const listeners = {};
let blurs = 0;
const window = {addEventListener(name, callback) { (listeners[name] ||= []).push(callback); }};
window.parent = {};
window.frames = [];
const document = {addEventListener() {}, activeElement: {blur() { blurs++; }}};
const context = vm.createContext({window, document});
function active(value) { vm.runInContext(code.replace('/*ACTIVE*/', String(value)), context); }
function key(name) {
    let prevented = false, stopped = false;
    listeners[name].forEach(fn => fn({preventDefault() { prevented = true; }, stopImmediatePropagation() { stopped = true; }}));
    return {prevented, stopped};
}
active(false);
for (const name of ['keydown', 'keyup', 'keypress', 'beforeinput']) assert.deepEqual(key(name), {prevented:true, stopped:true});
assert.equal(blurs, 1);
active(true);
assert.deepEqual(key('keydown'), {prevented:false, stopped:false});
active(false);
assert.deepEqual(key('keydown'), {prevented:true, stopped:true});
active(true);
assert.deepEqual(key('beforeinput'), {prevented:false, stopped:false});
window.frames.push({postMessage(value) { window.lastMessage = value; }});
active(false);
assert.equal(window.lastMessage.reelBreakInputActive, false);
listeners.message[0]({source:{},data:{reelBreakInputActive:true}});
assert.deepEqual(key('keydown'), {prevented:true, stopped:true}, 'unrelated frames cannot reactivate hidden input');
listeners.message[0]({source:window.parent,data:{reelBreakInputActive:true}});
assert.deepEqual(key('keydown'), {prevented:false, stopped:false});
assert.equal(window.lastMessage.reelBreakInputActive, true);
assert.equal(listeners.keydown.length, 1, 'repeated visibility updates install only one handler');
console.log('PASS: hidden browser blocks page keyboard handlers; explicit resume restores input');
